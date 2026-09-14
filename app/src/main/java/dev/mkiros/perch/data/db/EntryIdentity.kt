package dev.mkiros.perch.data.db

/**
 * The two columns an article can be recognised by (PLAN-12 §0.2, #69): the identity the
 * feed gave it and the address the page has. A feed item and a backfilled page are the same
 * article when either matches, so the backfill reads both — see [EntryDao.identitiesForFeed].
 */
data class EntryIdentity(
    val guid: String,
    val link: String?,
)
