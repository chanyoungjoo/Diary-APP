package com.example.diaryapplication.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatResponse (
    val refinedDiary : String,
    val summary : String,
    val emotionLabel : String,
    val emotionReason : String,
    val fortune : String,
    val keywords : List<String>,
    val counsel : String
)

class ChatRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    //private val serverUrl = "http://192.168.123.104:8000"
    private val serverUrl = "http://34.50.19.184:8000" // Google Cloud IP Address
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    val currentUid get() = auth.currentUser?.uid

    // 닉네임 불러오기 함수
    suspend fun getNickname(uid: String): String? {
        val doc = db.collection("users").document(uid).get().await()

        return doc.getString("nickname")
    }

    // 특정 날짜 일기에 저장된 상담 멘트(counsel)를 읽어오는 함수
    suspend fun getTodayCounsel(uid: String, date: String): String? {
        val result = db.collection("diaries")
            .whereEqualTo("user_id", uid)
            .whereEqualTo("diary_date", date)
            .limit(1)
            .get().await()

        return result.documents.firstOrNull()?.getString("counsel")
    }

    // 챗봇 서버에게 메시지를 전송하고 챗봇의 응답을 받는 함수
    suspend fun sendToChatServer(
        text: String,
        uid : String,
        date:String,
        relatedMemories: List<Map<String, String>> = emptyList()
    ): ChatResponse {
        return withContext(Dispatchers.IO) {

            val jsonBody = org.json.JSONObject().apply {
                put("user_id", uid)
                put("content", text)
                put("date", date)
                put("related_memories", org.json.JSONArray(
                    relatedMemories.map { org.json.JSONObject(it as Map<*, *>) }
                ))
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$serverUrl/diary/analyze")
                .post(jsonBody)
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = org.json.JSONObject(body)

                val emotionObj = json.getJSONObject("emotion")
                val keywords = json.getJSONArray("keywords")
                    .let { arr -> List(arr.length()) { arr.getString(it)} }

                ChatResponse (
                    refinedDiary = json.getString("refined_diary"),
                    summary = json.getString("summary"),
                    emotionLabel = emotionObj.getString("label"),
                    emotionReason = emotionObj.getString("reason"),
                    fortune = emotionObj.getString("fortune"),
                    keywords = keywords,
                    counsel = json.getString("counsel")
                )

            } else {
                throw Exception("서버 오류: ${response.code}")
            }
        }
    }

    suspend fun getRecentEmotionLogs(uid: String) : List<Map<String, String>> {
        val result = db.collection("diaries")
            .whereEqualTo("user_id", uid)
            .orderBy("diary_date", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(5)
            .get().await()

        return result.documents.mapNotNull { doc ->
            val date = doc.getString("diary_date") ?: return@mapNotNull null
            val summary = doc.getString("summary") ?: return@mapNotNull null
            val emotionDoc = db.collection("diaries").document(doc.id)
                .collection("emotion_result").document("result")
                .get().await()
            val finalEmotion = emotionDoc.getString("final_emotion") ?: ""

            mapOf(
                "date" to date,
                "summary" to summary,
                "tags" to finalEmotion  // "기쁨", "슬픔" 등 텍스트
            )
        }
    }
}