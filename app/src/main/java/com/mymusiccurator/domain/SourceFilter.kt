package com.mymusiccurator.domain

/** 음악이 아닌 콘텐츠도 재생하는 앱은 기록에서 제외한다 */
object SourceFilter {
    /** YouTube 앱과 그 변형들. YouTube Music(com.google.android.apps.youtube.music)은 음악 앱이라 수집한다. */
    val EXCLUDED_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "com.google.android.apps.youtube.kids",
        "com.google.android.apps.youtube.creator",
        "app.revanced.android.youtube",
        "com.vanced.android.youtube",
    )

    fun isExcluded(packageName: String): Boolean = packageName in EXCLUDED_PACKAGES
}
