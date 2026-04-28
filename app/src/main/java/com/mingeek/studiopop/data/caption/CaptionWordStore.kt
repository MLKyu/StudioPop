package com.mingeek.studiopop.data.caption

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * 세션 메모리 word-level 자막 보관소. STT 엔진(Vosk/whisper.cpp) 이 만들어낸 word 시간 정보가
 * SRT 파일로 직렬화되며 손실되는 걸 보완 — 같은 앱 실행 동안엔 이 store 가 word 시퀀스를 들고
 * 있다가 편집기에서 카라오케 자막을 만들 때 [wordsInRange] 로 매칭해 돌려준다.
 *
 * 키: 영상 source URI (string). 값: 시간순 정렬된 [CueWord] 평탄 리스트.
 *
 * 영속화 안 함 — 앱 재시작 시 store 비워지므로 이전 세션의 카라오케 자막은 자동 fakeWordTimings
 * 로 fallback. 사용자에겐 일관된 동작이지만 정확도가 떨어지므로, 정확도가 중요하면 같은 세션에서
 * STT → 편집을 끝내는 워크플로우 유지가 권장.
 */
class CaptionWordStore {

    private val byUri = ConcurrentHashMap<String, List<CueWord>>()

    /** STT 결과의 모든 cue.words 를 평탄화해서 보관. 비어 있으면 entry 자체를 만들지 않음. */
    fun putFromCues(uri: Uri, cues: List<Cue>) = putFromCuesByKey(uri.toString(), cues)

    /** 직접 [CueWord] 리스트로 보관. */
    fun put(uri: Uri, words: List<CueWord>) = putByKey(uri.toString(), words)

    /** 영상의 보관된 word 전체. 없으면 빈 리스트. */
    fun get(uri: Uri): List<CueWord> = getByKey(uri.toString())

    /**
     * 주어진 시간 범위 [startMs..endMs] 안에 들어오는 word 들. 부분 겹침은 허용 — word 의 시작이
     * 범위 안이면 포함. word 끝이 범위를 살짝 넘어도 자연스럽게 카라오케에 들어가도록 관대하게.
     */
    fun wordsInRange(uri: Uri, startMs: Long, endMs: Long): List<CueWord> =
        wordsInRangeByKey(uri.toString(), startMs, endMs)

    fun invalidate(uri: Uri) {
        byUri.remove(uri.toString())
    }

    fun clear() {
        byUri.clear()
    }

    // --- internal: 단위 테스트용 String 키 API. android.net.Uri 가 unit test 환경에서 호출 불가
    // 라 분리. 운영 코드에선 위 Uri 변형을 사용. ---

    internal fun putFromCuesByKey(key: String, cues: List<Cue>) {
        val words = cues.flatMap { it.words.orEmpty() }
        if (words.isEmpty()) return
        byUri[key] = words.sortedBy { it.startMs }
    }

    internal fun putByKey(key: String, words: List<CueWord>) {
        if (words.isEmpty()) return
        byUri[key] = words.sortedBy { it.startMs }
    }

    internal fun getByKey(key: String): List<CueWord> = byUri[key].orEmpty()

    internal fun wordsInRangeByKey(key: String, startMs: Long, endMs: Long): List<CueWord> {
        val all = byUri[key] ?: return emptyList()
        return all.filter { it.startMs in startMs..endMs }
    }
}
