package xyz.a202132.app.ui.navigation

object AppRoute {
    const val MAIN = "main"
    const val PER_APP_PROXY = "per_app_proxy"
    const val NODE_LIST = "node_list"
    const val NETWORK_TOOLBOX = "network_toolbox"
    const val UNLOCK_TEST = "unlock_test"
    const val OTHER_CONFIG = "other_config"
    const val LAN_PROXY = "lan_proxy"
    const val QR_SCANNER = "qr_scanner"
    const val RUNTIME_LOG = "runtime_log"
    const val SUBSCRIPTION_MANAGEMENT = "subscription_management"
    const val SUBSCRIPTION_GROUP_ADD = "subscription_group_add"
    const val SUBSCRIPTION_GROUP_EDIT_ARGUMENT = "groupId"
    const val SUBSCRIPTION_GROUP_EDIT = "subscription_group_edit/{$SUBSCRIPTION_GROUP_EDIT_ARGUMENT}"
    const val RULE_MANAGEMENT = "rule_management"
    const val RULE_DETAIL_ARGUMENT = "ruleId"
    const val RULE_DETAIL = "rule_detail/{$RULE_DETAIL_ARGUMENT}"

    fun ruleDetail(ruleId: String): String = "rule_detail/$ruleId"
    fun editSubscriptionGroup(groupId: String): String = "subscription_group_edit/$groupId"
}
