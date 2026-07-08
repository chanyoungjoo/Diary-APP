package com.example.diaryapplication.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.diaryapplication.repository.ChatRepository
import java.time.LocalDate

// 메시지 발신자를 구분
enum class Sender { BOT, USER }

// ChatMessage
// 채팅 목록 한 줄에 대응하는 데이터 클래스.
data class ChatMessage(
    val id: Long,
    val sender: Sender,
    val text: String,
    val time: String,
    val isLoading: Boolean = false
)

// ChatViewModel
// ChatScreen의 상태와 비즈니스 로직을 담당하는 ViewModel.
class ChatViewModel : ViewModel() {

    // Firebase 및 채팅 서버 통신을 담당하는 Repository
    private val repository = ChatRepository()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private var recentEmotionLogs: List<Map<String, String>> = emptyList()

    val inputText = MutableStateFlow("")

    // 전송 중 여부: true일 때 전송 버튼·입력창 비활성화 (사용자 중복 전송 방지)
    val isSending = MutableStateFlow(false)

    // 포춘쿠키 메시지: 서버 응답의 fortune 필드 값이 저장됨
    // ChatScreen에서 isCounselingEnd && fortune.isNotEmpty() 조건을 모두 만족할 때 카드 표시
    val fortuneMessage = MutableStateFlow("")

    // 요약 메시지
    val summaryMessage = MutableStateFlow("")

    init {

    }

    // 환영 메시지 로드
    private fun loadWelcomeMessage() {
        val uid = repository.currentUid ?: run {
            // 로그인 정보 없음 → 닉네임 없는 기본 메시지
            addBotMessage("안녕하세요! 😊\n오늘 하루는 어떠셨나요?\n무엇이든 편하게 이야기해주세요.")
            return
        }
        viewModelScope.launch {
            try {
                val nickname = repository.getNickname(uid) ?: ""

                // 오늘 일기 저장 시 서버가 일기 기반으로 생성해 둔 상담 멘트(counsel)를 읽어옴
                val counsel = repository.getTodayCounsel(uid, LocalDate.now().toString())

                if (!counsel.isNullOrBlank()) {
                    // 일기 기반 오프닝
                    addBotMessage("안녕하세요, ${nickname}님! 😊\n오늘 일기 잘 읽었어요.\n${counsel}")
                } else {
                    // 오늘 작성한 일기가 없으면 일반 인사로 시작
                    addBotMessage(
                        "안녕하세요, ${nickname}님! 😊\n" +
                        "오늘 하루는 어떠셨나요?\n" +
                        "무엇이든 편하게 이야기해주세요!"
                    )
                }
            } catch (e: Exception) {
                // Firebase 통신 실패 → 기본 메시지로 대체
                addBotMessage("안녕하세요! 😊\n오늘 하루는 어떠셨나요?")
            }
        }
    }

    // 최근 감정 로그 로드
    private fun loadRecentEmotionLogs() {
        val uid = repository.currentUid ?: return
        viewModelScope.launch {
            try {
                recentEmotionLogs = repository.getRecentEmotionLogs(uid)
            } catch (e: Exception) {
                // 실패해도 빈 리스트로 동작 (채팅 기능에 영향 없음)
            }
        }
    }

    fun sendMessage() {
        val text = inputText.value.trim()
        if (text.isEmpty() || isSending.value) return // 빈 입력 또는 중복 전송 방지

        inputText.value = ""      // 입력창 즉시 비움
        addUserMessage(text)      // 유저 말풍선 바로 표시
        isSending.value = true    // 전송 중 상태 → 버튼 비활성화

        viewModelScope.launch {
            delay(500)
            val loadingId = System.currentTimeMillis() + 1 // 직전 메시지 ID와 겹치지 않도록 +1
            addLoadingBubble(loadingId)

            try {
                val uid  = repository.currentUid ?: ""
                val date = LocalDate.now().toString() // "YYYY-MM-DD"

                val response = repository.sendToChatServer(
                    text            = text,
                    uid             = uid,
                    date            = date,
                    relatedMemories = recentEmotionLogs // 이전에 로드해 둔 감정 로그
                )

                removeMessage(loadingId)           // 로딩 말풍선 제거
                addBotMessage("${response.counsel}") // 봇 응답 말풍선 추가
                fortuneMessage.value = response.fortune // 포춘쿠키 텍스트 저장

            } catch (e: Exception) {
                removeMessage(loadingId)
                addBotMessage("죄송합니다, 잠시 후 다시 시도해주세요")
            } finally {
                isSending.value = false // 성공·실패 무관하게 전송 상태 해제
            }
        }
    }

    // 메시지 헬퍼 함수
    // 현재 시각을 "HH:mm" 형식으로 반환 (말풍선 하단 시각 표시용)
    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    // 봇 말풍선을 목록 끝에 추가
    private fun addBotMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(
            id     = System.currentTimeMillis(),
            sender = Sender.BOT,
            text   = text,
            time   = now()
        )
    }

    // 유저 말풍선을 목록 끝에 추가
    private fun addUserMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(
            id     = System.currentTimeMillis(),
            sender = Sender.USER,
            text   = text,
            time   = now()
        )
    }

    // 로딩 말풍선(". . ." 점 애니메이션)을 목록 끝에 추가
    // id를 직접 받아 나중에 removeMessage(loadingId)로 정확히 제거할 수 있도록 함
    private fun addLoadingBubble(id: Long) {
        _messages.value = _messages.value + ChatMessage(
            id        = id,
            sender    = Sender.BOT,
            text      = ". . .",
            time      = now(),
            isLoading = true // LoadingBubble 컴포저블로 렌더링됨
        )
    }

    // 지정 id의 메시지를 목록에서 제거 (주로 로딩 말풍선 제거에 사용)
    private fun removeMessage(id: Long) {
        _messages.value = _messages.value.filter { it.id != id }
    }

    // 입력창 텍스트 업데이트
    fun onInputChange(text: String) {
        inputText.value = text
    }

    // 채팅 초기화 (resetChat)
    // ChatScreen의 LaunchedEffect(Unit)에서 화면 진입마다 호출됨
    fun resetChat() {
        _messages.value      = emptyList()
        inputText.value      = ""
        isSending.value      = false
        fortuneMessage.value = ""
        summaryMessage.value = ""
        loadWelcomeMessage()    // Firebase에서 닉네임을 가져와 개인화 환영 메시지 표시
        loadRecentEmotionLogs() // 서버 전송 시 첨부할 최근 감정 로그 미리 로드
    }
}
