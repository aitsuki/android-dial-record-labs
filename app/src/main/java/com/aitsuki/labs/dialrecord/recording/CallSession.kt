package com.aitsuki.labs.dialrecord.recording

/** 本 App 的会话生命周期；状态变更由服务在主线程串行执行。 */
internal class CallSession {
    // CALL_ACTIVE 包括已发起但尚未接通的拨号阶段，与录音器是否启动成功无关。
    enum class State { IDLE, WAITING_FOR_OFFHOOK, CALL_ACTIVE, FINALIZING }
    enum class PhoneState { IDLE, RINGING, OFFHOOK }
    enum class Effect { NONE, START_RECORDING, BEGIN_FINALIZATION }

    @Volatile
    var state = State.IDLE
        private set

    val canStartDial: Boolean
        get() = state == State.IDLE || state == State.WAITING_FOR_OFFHOOK

    val hasSession: Boolean
        get() = state != State.IDLE

    val isWaitingForOffHook: Boolean
        get() = state == State.WAITING_FOR_OFFHOOK

    fun begin() {
        check(state == State.IDLE) { "开始新会话前必须清理旧会话" }
        state = State.WAITING_FOR_OFFHOOK
    }

    fun onPhoneState(phoneState: PhoneState): Effect {
        return when {
            isWaitingForOffHook && phoneState == PhoneState.OFFHOOK -> {
                state = State.CALL_ACTIVE
                Effect.START_RECORDING
            }
            // 等待拨号时先收到来电，放弃本次等待，不能把来电当成自己的去电。
            (isWaitingForOffHook && phoneState == PhoneState.RINGING) ||
                (state == State.CALL_ACTIVE && phoneState == PhoneState.IDLE) -> {
                beginFinalizing()
                Effect.BEGIN_FINALIZATION
            }
            else -> Effect.NONE
        }
    }

    fun beginFinalizing() {
        if (hasSession) state = State.FINALIZING
    }

    /** 仅在录音、监听和通话记录关联全部清理后恢复空闲。 */
    fun reset() {
        state = State.IDLE
    }
}
