package io.github.ccch1mneyyy.dsh_app.phone.a11y

/**
 * 本 fork 的实验开关（非上游功能）：让读屏链路在「空壳 / 文字太少」时不再 fail-closed。
 *
 * 背景：上游把 accessibilityDataSensitive 造成的空壳视图判为 unreadable，并据此拒绝返回
 * 节点、拒绝 collect、否决 OCR。OpenMinis 在相同权限声明下 fail-open，能读到微信聊天。
 * 此开关只用于在本机验证「到底是哪一层门挡住了微信」，不是上游认可的行为。
 *
 * 保留的系统级红线：hiddenWindowTitle（FLAG_SECURE / 系统直接不给 root）不受影响 ——
 * 那种情况节点和截图都拿不到，放开代码也没有用。
 *
 * @Volatile 便于 debug probe 运行时切换，做开 / 关 A-B 对照。
 */
internal object ReadCompat {
    @Volatile
    var relaxed: Boolean = true

    /** 仅 debug probe 使用：on | off | 其他值只查询 */
    fun toggle(cmd: String?): String {
        when (cmd) {
            "on" -> relaxed = true
            "off" -> relaxed = false
        }
        return "readCompat=${if (relaxed) "on" else "off"}"
    }
}
