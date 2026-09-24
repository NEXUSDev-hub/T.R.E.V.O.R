package com.trevor.assistant

/** Part 13: centralized mode contracts. */
data class TrevorModeContract(
    val mode: TrevorMode,
    val label: String,
    val acceptsAttachments: Boolean,
    val allowsTerminal: Boolean,
    val requiresFreshInfo: Boolean
)

object TrevorModeRegistry {
    private val contracts = mapOf(
        TrevorMode.NORMAL to TrevorModeContract(TrevorMode.NORMAL, "Normal", true, false, false),
        TrevorMode.ANALYSE to TrevorModeContract(TrevorMode.ANALYSE, "Analyse", true, false, false),
        TrevorMode.RESEARCH to TrevorModeContract(TrevorMode.RESEARCH, "Research", true, false, true),
        TrevorMode.PROJECT to TrevorModeContract(TrevorMode.PROJECT, "Project", true, false, false),
        TrevorMode.RATIO_SHIFTER to TrevorModeContract(TrevorMode.RATIO_SHIFTER, "Ratio Shifter", false, false, false),
        TrevorMode.TERMINAL to TrevorModeContract(TrevorMode.TERMINAL, "Terminal", false, true, false)
    )

    fun contract(mode: TrevorMode): TrevorModeContract = contracts.getValue(mode)

    fun validate(mode: TrevorMode, attachment: TrevorAttachment?): Result<Unit> {
        val c = contract(mode)
        if (attachment != null && !c.acceptsAttachments) {
            return Result.failure(IllegalArgumentException("This mode does not accept attachments."))
        }
        return Result.success(Unit)
    }
}
