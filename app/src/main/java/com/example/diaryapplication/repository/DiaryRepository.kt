package com.example.diaryapplication.repository

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import com.example.diaryapplication.model.DiaryEntry
import com.example.diaryapplication.repository.ChatResponse
import com.google.firebase.database.FirebaseDatabase

// 감정 분석 서버(/analyze)가 반환하는 JSON을 담는 구조체.
data class EmotionAnalysis(
    val emotion: String,               // 최종 감정 라벨 ("emotion")
    val prob: Map<String, Double>,     // 감정별 확률 ("prob")
    val textEmotion: String,           // 텍스트 기반 감정 ("text_emotion")
    val videoEmotion: String,          // 영상 기반 감정 ("video_emotion", 없으면 "")
    val videoPath: String              // 서버 저장 경로 ("video_path")
)


class DiaryRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val currentUid get() = auth.currentUser?.uid

    // 이번 달 이모지 MAP 가져오는 함수
    suspend fun getMonthEmojis(uid: String, year: Int, month: Int): Map<LocalDate, String> {
        val prefix = "%04d-%02d".format(year, month)

        val result = db.collection("diaries")
            .whereEqualTo("user_id", uid)
            .whereGreaterThanOrEqualTo("diary_date", "${prefix}-01")
            .whereLessThanOrEqualTo("diary_date", "${prefix}-31")
            .get().await()

        val map = mutableMapOf<LocalDate, String>()

        result.documents.forEach { doc ->
            val dateStr = doc.getString("diary_date") ?: return@forEach
            val emoji = doc.getString("emotion_emoji") ?: return@forEach
            if (emoji.isNotEmpty()) {
                map[LocalDate.parse(dateStr)] = emoji
            }
        }

        return map
    }

    // 특정 날짜의 일기를 불러오는 함수
    suspend fun getDiary(uid: String, dateStr: String): DiaryEntry? {
        val result = db.collection("diaries")
            .whereEqualTo("user_id", uid)
            .whereEqualTo("diary_date", dateStr)
            .limit(1)
            .get().await()

        val doc = result.documents.firstOrNull() ?: return null

        return DiaryEntry(
            id = doc.id,
            diaryDate = doc.getString("diary_date") ?: "",
            content = doc.getString("content") ?: "",
            weather = doc.getString("weather") ?: "SUNNY",
            exerciseMin = (doc.getLong("exercise_min") ?: 0).toInt(),
            studyMin = (doc.getLong("study_min") ?: 0).toInt(),
            routine = doc.getString("routine") ?: "",
            bestThing = doc.getString("best_thing") ?: "",
            regretThing = doc.getString("regret_thing") ?: "",
            imageUrl = doc.getString("image_url") ?: "",
            emotionEmoji = doc.getString("emotion_emoji") ?: ""
        )
    }

    // 새로운 일기를 저장하는 함수
    // 새롭게 저장된 일기의 ID를 반환
    suspend fun addDiary(data: HashMap<String, Any>): String {
        val newDoc = db.collection("diaries").add(data).await()
        return newDoc.id
    }


    // 기존의 일기의 내용을 수정하는 함수
    suspend fun updateDiary(diaryId: String, data: HashMap<String, Any>) {
        db.collection("diaries").document(diaryId)
            .update(data as Map<String, Any>).await()
    }

    // AI 서버가 감정 분석한 결과를
    // DB에 저장하는 함수
    suspend fun saveEmotionResult(
        diaryId: String,
        finalEmotion: String,
        textEmotion: String,
        videoEmotion: String,
        prob: Map<String, Double>
    ) {
        db.collection("diaries").document(diaryId)
            .collection("emotion_result").document("result")
            .set(mapOf(
                "final_emotion" to finalEmotion,
                "text_emotion" to textEmotion,
                "video_emotion" to videoEmotion,
                "prob" to prob,
                "created_at" to com.google.firebase.Timestamp.now()
            )).await()
    }

    // Firebase Realtim DB
    // ESP32 읽기 전용
    suspend fun saveEmotionToRTDB(finalEmotion: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().reference
            .child("emotion_latest")
            .child(uid)
            .child("final_emotion")
            .setValue(finalEmotion)
            .await()
    }

    // 메인 홈 화면에 스트릿 데이터를 표시하기 위해
    // 이번 달에 일기를 작성한 일자를 가져오는 함수
    suspend fun getMonthWrittenDates(uid: String, prefix: String): Set<String> {
        val result = db.collection("diaries")
            .whereEqualTo("user_id", uid)
            .whereGreaterThanOrEqualTo("diary_date", "${prefix}-01")
            .whereLessThanOrEqualTo("diary_date", "${prefix}-31")
            .get().await()

        return result.documents
            .mapNotNull { it.getString("diary_date") }
            .toSet()
    }

    // DB에 이미지를 업로드 하는 함수
    // 반환은 다운로드 URL을 반환
    suspend fun uploadImage(uid: String, dateStr: String, uri: Uri, context : Context): String {
        return withContext(Dispatchers.IO) {

            // Content URI에서 바이트 직접 읽기
            val bytes = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
                ?: throw Exception("이미지 읽기 실패")

            // 바이트로 Firebase Storage에 직접 업로드
            val refer = storage.reference.child("diaries/$uid/$dateStr.jpg")
            refer.putBytes(bytes).await()

            refer.downloadUrl.await().toString()

        }
    }


    // 작성한 일기의 텍스트와 전면 카메라로 촬영한 얼굴 영상을 AI 서버로 보내는 함수
    // 성공 시에는 감정 분석 결과 전체(EmotionAnalysis)를 반환하며, 실패 시 null을 반환
    suspend fun sendVideoToServer(
        videoFile: File,
        diaryText: String,
        uid: String,
        diaryDate: String
    ): EmotionAnalysis? {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("text", diaryText)
                    .addFormDataPart("user_id", uid)
                    .addFormDataPart("diary_date", diaryDate)
                    .addFormDataPart(
                        name = "video",
                        filename = videoFile.name,
                        body = videoFile.asRequestBody("video/mp4".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    //.url("http://192.168.123.104:8080/analyze")
                    .url("http://34.50.19.184:8080/analyze") // Google Cloud IP Address
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = org.json.JSONObject(body)

                    // 서버가 명시적으로 success가 아니면 처리
                    if (json.optString("status") != "success") return@withContext null

                    // "prob"는 { 라벨: 확률 } 형태의 JSON 객체 → Map<String, Double>로 변환
                    val probJson = json.optJSONObject("prob")
                    val prob = mutableMapOf<String, Double>()
                    if (probJson != null) {
                        val keys = probJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            prob[key] = probJson.getDouble(key)
                        }
                    }

                    EmotionAnalysis(
                        emotion      = json.getString("emotion"),
                        prob         = prob,
                        textEmotion  = json.optString("text_emotion", ""),
                        videoEmotion = json.optString("video_emotion", ""),
                        videoPath    = json.optString("video_path", "")
                    )
                } else null
            } catch (e: Exception) {
                null // 네트워크 오류/파싱 오류 시 null 반환
            } finally {
                if (videoFile.exists()) videoFile.delete() // 임시 파일 삭제
            }
        }
    }

    // 일기 요약 + 상담 멘트(counsel)를 저장하는 함수
    // counsel: 일기 저장 시 챗봇 서버가 일기 기반으로 생성한 상담 멘트
    suspend fun saveSummary(diaryId : String, summary : String , keywords : List<String>, counsel : String){
        db.collection("diaries").document(diaryId)
            .update(mapOf(
                "summary" to summary,
                "keywords" to keywords,
                "counsel" to counsel
            )).await()
    }

    // 서버에서 저장한 emotion_result값을 읽는 함수
    suspend fun getEmotionResult(diaryId: String) : String? {
        val doc = db.collection("diaries").document(diaryId)
            .collection("emotion_result").document("result")
            .get().await()

        return doc.getString("final_emotion")
    }

    // 앱에서 감정에 따른 이모지로 변환 후에 DB에 저장하는 함수
    suspend fun updateEmotionEmoji(diaryId: String, emotionEmoji: String) {
        db.collection("diaries").document(diaryId)
            .update("emotion_emoji", emotionEmoji).await()
    }

}