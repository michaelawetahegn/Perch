package dev.mkiros.perch.data.repo

/**
 * Folder name → id, created on first mention and remembered afterwards.
 *
 * Matching is [FolderRepository.createFolder]'s — case-insensitive, space-trimmed — so a
 * file whose "Graphics" meets an existing "graphics" files sources into the folder the
 * reader can already see instead of a near-duplicate beside it. [created] counts only
 * folders that genuinely did not exist, which is what makes a second import able to
 * report zero.
 *
 * One resolver per import, and one import per resolver: the map is the memory of what
 * this file has already asked for, so both importers (U13's OPML and U14's profile)
 * resolve a name the same way and count the same way.
 */
internal class FolderResolver(private val folders: FolderRepository) {
    private val byKey = mutableMapOf<String, Long>()
    var created = 0
        private set

    suspend fun idOf(name: String?): Long? {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = clean.lowercase()
        byKey[key]?.let { return it }
        val id = folders.findFolderNamed(clean)?.id
            ?: folders.createFolder(clean).also { created++ }
        byKey[key] = id
        return id
    }
}
