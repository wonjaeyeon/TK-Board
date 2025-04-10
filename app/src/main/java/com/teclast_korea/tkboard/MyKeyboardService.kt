package com.teclast_korea.tkboard


import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 간단한 Gboard 유사 레이아웃 (숫자열, QWERTY, 한글 자모) + 하단 30~40% 차지
 * 두벌식 한글 자모 간단 조합 로직 포함
 */
class MyKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private lateinit var rootContainer: View           // ime_container.xml 루트
    private var keyboardView: KeyboardView? = null

    // 자판 종류
    private var keyboardEng: Keyboard? = null
    private var keyboardKor: Keyboard? = null
    private var keyboardNum: Keyboard? = null

    // 현재 활성 자판 모드
    private enum class Mode { ENG, KOR, NUM }
    private var currentMode = Mode.ENG

    // 쉬프트 여부
    private var isShift = false

    // 한글 자모 조합기
    private val hangulComposer = HangulComposer()

    override fun onCreateInputView(): View {
        // 1) ime_container.xml을 inflate (하단 30~40% 만 차지)
        rootContainer = layoutInflater.inflate(R.layout.ime_container, null)

        // 2) keyboard_view.xml 을 그 안의 FrameLayout(containerKeyboard)에 동적으로 추가
        val container = rootContainer.findViewById<FrameLayout>(R.id.containerKeyboard)
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, container, false) as KeyboardView
        container.addView(keyboardView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 3) placeholder XML 불러오기
        keyboardEng = Keyboard(this, R.xml.placeholder_keyboard)
        keyboardKor = Keyboard(this, R.xml.placeholder_keyboard_kor)
        keyboardNum = Keyboard(this, R.xml.placeholder_keyboard_num) // 만약 만들었다면

        // 초기 영문 자판
        setKeyboardMode(Mode.ENG)

        keyboardView?.setOnKeyboardActionListener(this)
        return rootContainer
    }

    /**
     * 자판 모드 변경
     */
    private fun setKeyboardMode(mode: Mode) {
        currentMode = mode
        isShift = false

        val kb = when(mode) {
            Mode.ENG -> keyboardEng
            Mode.KOR -> keyboardKor
            Mode.NUM -> keyboardNum ?: keyboardEng // 숫자 자판이 없으면 일단 영문
        }

        keyboardView?.keyboard = kb
        keyboardView?.invalidateAllKeys()
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        when (primaryCode) {
            // 쉬프트
            -1 -> {
                isShift = !isShift
                toggleShift() // 대소문자 토글
            }
            // 한/영 전환
            -101 -> {
                if (currentMode == Mode.KOR) {
                    setKeyboardMode(Mode.ENG)
                } else {
                    setKeyboardMode(Mode.KOR)
                }
                // 혹시 한글 조합 중이던 것을 commit
                commitHangul()
            }
            // 숫자/기호 전환
            -2 -> {
                if (currentMode == Mode.NUM) {
                    // 다시 영문으로
                    setKeyboardMode(Mode.ENG)
                } else {
                    setKeyboardMode(Mode.NUM)
                }
                // 한글 조합 중이면 commit
                commitHangul()
            }
            // 백스페이스
            Keyboard.KEYCODE_DELETE, -5 -> {
                // 만약 한글 조합 중이라면, 조합 취소/되돌리기
                if (currentMode == Mode.KOR && hangulComposer.isComposing()) {
                    hangulComposer.delete()
                    if (hangulComposer.isComposing()) {
                        currentInputConnection.setComposingText(hangulComposer.getCurrentText(), 1)
                    } else {
                        // 모두 지웠으면
                        currentInputConnection.deleteSurroundingText(1, 0)
                    }
                } else {
                    currentInputConnection.deleteSurroundingText(1, 0)
                }
            }
            // 엔터
            Keyboard.KEYCODE_DONE, -4 -> {
                commitHangul()
                currentInputConnection.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                )
                currentInputConnection.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                )
            }
            // 스페이스
            32 -> {
                // 한글 조합 중이면 확정 후 스페이스
                commitHangul()
                currentInputConnection.commitText(" ", 1)
            }
            else -> {
                handleCharacter(primaryCode)
            }
        }
    }

    override fun onText(text: CharSequence?) {
        // 다중 문자 입력 (쓰지 않는다면 무시 가능)
        if (!text.isNullOrEmpty()) {
            commitHangul()
            currentInputConnection.commitText(text, 1)
        }
    }

    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}

    /**
     * 쉬프트 (대소문자) 토글
     */
    private fun toggleShift() {
        val kb = keyboardView?.keyboard ?: return
        val isUpper = isShift
        val keys = kb.keys
        for (key in keys) {
            val code = key.codes.firstOrNull() ?: continue
            // 알파벳 소문자 범위(a=97 ~ z=122)라면 대문자로 변경
            if (code in 97..122) {
                val char = if (isUpper) {
                    (code - 32).toChar()  // 소문자 -> 대문자
                } else {
                    code.toChar()        // 소문자 그대로
                }
                key.label = char.toString()
                key.codes = intArrayOf(if (isUpper) code - 32 else code)
            }
        }
        keyboardView?.invalidateAllKeys()
    }

    /**
     * 일반 문자 키 처리
     */
    private fun handleCharacter(primaryCode: Int) {
        when (currentMode) {
            Mode.KOR -> {
                // 한글 자모 조합
                if (hangulComposer.input(primaryCode)) {
                    currentInputConnection.setComposingText(hangulComposer.getCurrentText(), 1)
                } else {
                    // 자모가 아닌 ASCII 등 들어왔으면 바로 확정 후 입력
                    commitHangul()
                    val codeChar = primaryCode.toChar()
                    currentInputConnection.commitText(codeChar.toString(), 1)
                }
            }
            else -> {
                // 영문, 숫자 모드에서는 즉시 커밋
                val codeChar = primaryCode.toChar()
                if (isShift && codeChar in 'a'..'z') {
                    // 쉬프트 적용된 상태면 대문자
                    val upperChar = codeChar.uppercaseChar()
                    currentInputConnection.commitText(upperChar.toString(), 1)
                } else {
                    currentInputConnection.commitText(codeChar.toString(), 1)
                }
            }
        }
    }

    /**
     * 현재 조합 중이던 한글을 확정(commit)하고 조합기 초기화
     */
    private fun commitHangul() {
        if (hangulComposer.isComposing()) {
            val text = hangulComposer.getCurrentText()
            currentInputConnection.commitText(text, 1)
            hangulComposer.reset()
        }
    }
}

/**
 * 간단한 두벌식 한글 자모 조합기 예시
 * (실제 상용 키보드처럼 모든 케이스를 완벽 지원하진 않음)
 */
class HangulComposer {

    // 유니코드 한글
    // 초성(19), 중성(21), 종성(28) 표
    private val CHO = listOf(
        0x1100, 0x1101, 0x1102, 0x1103, 0x1104, 0x1105, 0x1106,
        0x1107, 0x1108, 0x1109, 0x110A, 0x110B, 0x110C, 0x110D,
        0x110E, 0x110F, 0x1110, 0x1111, 0x1112
    )
    private val JUN = listOf(
        0x1161, 0x1162, 0x1163, 0x1164, 0x1165, 0x1166, 0x1167,
        0x1168, 0x1169, 0x116A, 0x116B, 0x116C, 0x116D, 0x116E,
        0x116F, 0x1170, 0x1171, 0x1172, 0x1173, 0x1174, 0x1175
    )
    private val JON = listOf(
        0x0000, 0x11A8, 0x11A9, 0x11AA, 0x11AB, 0x11AC, 0x11AD,
        0x11AE, 0x11AF, 0x11B0, 0x11B1, 0x11B2, 0x11B3, 0x11B4,
        0x11B5, 0x11B6, 0x11B7, 0x11B8, 0x11B9, 0x11BA, 0x11BB,
        0x11BC, 0x11BD, 0x11BE, 0x11BF, 0x11C0, 0x11C1, 0x11C2
    )

    private var choIndex = -1
    private var junIndex = -1
    private var jonIndex = -1

    fun reset() {
        choIndex = -1
        junIndex = -1
        jonIndex = -1
    }

    fun isComposing(): Boolean {
        return (choIndex != -1 || junIndex != -1 || jonIndex != -1)
    }

    /**
     * 자모 입력
     * @return true면 composing 중, false면 자모가 아닌 ASCII 등
     */
    fun input(code: Int): Boolean {
        // 자모 범위인지 검사 (초/중/종)
        val isCho = CHO.indexOf(code) != -1
        val isJun = JUN.indexOf(code) != -1
        val isJon = JON.indexOf(code) != -1

        if (!isCho && !isJun && !isJon) {
            return false
        }

        if (isCho) {
            if (junIndex == -1) {
                // 아직 중성이 없으면 초성 입력
                choIndex = CHO.indexOf(code)
            } else if (jonIndex == -1) {
                // 이미 초,중 있는데 새 초성이 들어오면 => 기존 확정 후 새 초성
                // 간단 구현: 그냥 확정치고 reset
                // 실 구현이라면 "오타수정" 등 고려
                // 여기선 간단 처리
                return false
            } else {
                // 이미 종성 있는데 초성 오면 => 마찬가지로 확정 후 새 초성
                return false
            }
        } else if (isJun) {
            if (choIndex == -1) {
                // 초성이 없는데 중성만 들어오면 임시로 초성 ㅇ(11A8)으로 가정?
                // 실제 키보드마다 처리 다름. 여기선 간단히 초성 채워넣음.
                choIndex = 11 // ㅇ
            }
            if (jonIndex != -1) {
                // 이미 종성이 있으면 => 새 글자로 넘어가야 하는 상황
                return false
            }
            junIndex = JUN.indexOf(code)
        } else {
            // 종성
            if (choIndex == -1) {
                choIndex = 11 // ㅇ
            }
            if (junIndex == -1) {
                junIndex = 0  // ㅏ
            }
            jonIndex = JON.indexOf(code)
        }

        return true
    }

    /**
     * 자모 조합 → 완성형
     */
    fun getCurrentText(): String {
        if (choIndex == -1) return ""
        if (junIndex == -1) return CHO[choIndex].toChar().toString() // 초성만 있는 경우
        val base = 0xAC00
        val choOffset = choIndex * 21 * 28
        val junOffset = junIndex * 28
        val jonOffset = if (jonIndex == -1) 0 else (jonIndex)
        val codePoint = base + choOffset + junOffset + jonOffset
        return codePoint.toChar().toString()
    }

    /**
     * 종성 지우기 -> 중성 지우기 -> 초성 지우기 순
     */
    fun delete() {
        when {
            jonIndex != -1 -> {
                jonIndex = -1
            }
            junIndex != -1 -> {
                junIndex = -1
            }
            choIndex != -1 -> {
                choIndex = -1
            }
        }
    }
}