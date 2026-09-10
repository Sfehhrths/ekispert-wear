package dev.sfehhrths.ekispertwear.tap

/**
 * Mirror of `CompanionBridge` in the Morphe extension (ekispert-patches). Keep in sync.
 */
object TapContract {
    const val ACTION = "dev.sfehhrths.ekispertwear.action.TAP"

    /** The patched 駅すぱあと app; the only accepted broadcast sender. */
    const val SENDER_PACKAGE = "jp.co.val.expert.android.aio"

    const val EXTRA_KIND = "kind"
    const val EXTRA_URL = "url"
    const val EXTRA_HTTP_CODE = "http_code"
    const val EXTRA_CONTENT_TYPE = "content_type"
    const val EXTRA_BODY_GZIP = "body_gzip"
    const val EXTRA_BODY_LENGTH = "body_length"
    const val EXTRA_TIMESTAMP = "timestamp"
    const val EXTRA_COURSE_INDEX = "course_index"
    const val EXTRA_PRESENTER = "presenter"

    const val KIND_HTTP_RESPONSE = "http_response"
    const val KIND_TRANSFER_ALARM_COURSE = "transfer_alarm_course"
    const val KIND_SELECTED_COURSE = "selected_course"
    const val KIND_MYCLIP_COURSE = "myclip_course"
    const val KIND_DETAIL_OPENED = "detail_opened"

    /** URL path of the route search API whose ResultSet the selected-course index refers to. */
    const val ROUTE_SEARCH_PATH = "/v1/xml/closed/search/course/extreme"

    /** 運行情報 (rescuenow) XML; fetched at app start and when a detail screen starts (5 min cache). */
    const val SERVICE_INFO_PATH = "/v1/xml/closed/operationLine/service/rescuenow/information"

    /** mixway realtime train position / delay JSON; every 60 s while a detail page is visible. */
    const val REALTIME_TRIP_PATH = "/v1/json/realtime/trip"

    /** Detail-screen presenters whose page index refers to the latest route search result. */
    val ROUTE_SEARCH_PRESENTERS = setOf(
        "DISRxDiaSearchResultDetailParentFragmentPresenter",
        "DISRxDetourSearchResultDetailParentFragmentPresenter",
    )
}
