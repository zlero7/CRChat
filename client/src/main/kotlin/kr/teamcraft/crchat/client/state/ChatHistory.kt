package kr.teamcraft.crchat.client.state

/**
 * 위/아래 화살표로 예전에 보낸 메시지를 다시 불러오는 기능 (바닐라 채팅과 동일한 동작).
 * 세션 메모리에만 있다 — 재접속하면 비워진다.
 */
object ChatHistory {

    private val entries = mutableListOf<String>()

    /** -1 이면 히스토리 밖(직접 입력 중)이다. 0 이 가장 최근에 보낸 메시지. */
    private var cursor = -1

    /** 히스토리 탐색을 시작하기 직전, 입력창에 남아 있던 내용 — 아래로 끝까지 내리면 여기로 돌아온다. */
    private var draft = ""

    /** 메시지를 보낼 때마다 호출한다. 같은 내용을 또 보내면 최신 자리로 끌어올린다. */
    fun record(text: String) {
        entries.remove(text)
        entries.add(text)
        cursor = -1
    }

    /** 위 화살표. `current` 는 히스토리 진입 직전 입력창 내용(draft 로 저장해 둔다). */
    fun previous(current: String): String? {
        if (entries.isEmpty()) return null
        if (cursor == -1) draft = current
        cursor = (cursor + 1).coerceAtMost(entries.size - 1)
        return entries[entries.size - 1 - cursor]
    }

    /** 아래 화살표. 히스토리 밖까지 내려가면 draft 를 돌려주고 커서를 리셋한다. */
    fun next(): String? {
        if (cursor == -1) return null
        cursor--
        return if (cursor == -1) draft else entries[entries.size - 1 - cursor]
    }
}
