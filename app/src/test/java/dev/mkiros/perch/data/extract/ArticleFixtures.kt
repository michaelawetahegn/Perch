package dev.mkiros.perch.data.extract

import java.io.File

/**
 * One harvested article page and what a correct extraction of it looks like (U10).
 *
 * The two sentences are the whole point of the shape. A *mid-article* sentence proves the
 * extractor found the body rather than a teaser near the top; a *final* sentence proves it
 * kept going to the end, because an extractor that silently truncates at the first
 * `<aside>` still passes any "contains the article's opening" check. [excludes] is the
 * chrome that shares the page with the body — nav, footer, cookie banner, related-posts —
 * and is the half of the job that scoring alone does not do.
 *
 * @param cms the page's generator, recorded because the fallback chain is really a bet
 *   about how a handful of CMSes mark up an article body; the set has to stay varied.
 */
data class ArticleFixture(
    val slug: String,
    val url: String,
    val cms: String,
    val mid: String,
    val last: String,
    val excludes: List<String>,
) {
    fun html(): String = File(ArticleFixtures.dir(), "$slug.html").readText()
}

/**
 * The sixteen pages U10 is measured against: five from fabiensanglard.net (§0's "no body
 * at all" shape), five from gpuopen.com (§0's "excerpt standing in for the body"), and
 * six more excerpt-only sources drawn from the corpus by measurement — the five feeds
 * whose median item body is shortest after those two, plus V09's Squarespace page — so the
 * CMS coverage the task asks for is real rather than assumed.
 */
object ArticleFixtures {

    private const val FS = "https://fabiensanglard.net"
    private const val GPU = "https://gpuopen.com/learn"

    /** Chrome every AMD page carries, hoisted because all five repeat it. */
    private val AMD_CHROME = listOf(
        "Related news and technical articles",
        "©2026 Advanced Micro Devices, Inc.",
        "Cookie Settings",
    )

    /** The five fabiensanglard.net entries, whose feed ships title + link + date only. */
    val noBody: List<ArticleFixture> = listOf(
        ArticleFixture(
            slug = "fabiensanglard-tb4",
            url = "$FS/tb4/index.html",
            cms = "hand-rolled static",
            mid = "Fast forward to 2025, when I needed to replace my aging BenQ and its dead pixels.",
            last = "To this day, I have no idea why it works.",
            excludes = listOf("Fabien Sanglard - WEBSITE"),
        ),
        ArticleFixture(
            slug = "fabiensanglard-extinct",
            url = "$FS/extinct/index.html",
            cms = "hand-rolled static",
            mid = "There is little excuse for poor commit messages now.",
            last = "won the Academy Award for Best Visual Effects",
            excludes = listOf("Fabien Sanglard - WEBSITE"),
        ),
        ArticleFixture(
            slug = "fabiensanglard-jurassic-park-computers",
            url = "$FS/jurrasic_park_computers/index.html",
            cms = "hand-rolled static",
            mid = "The hardware of the Motorola Envoy included a Motorola Dragon I/68349 microprocessor",
            last = "One last detail for the road.",
            excludes = listOf("Fabien Sanglard - WEBSITE"),
        ),
        ArticleFixture(
            slug = "fabiensanglard-quake-asm",
            url = "$FS/quake_asm_optimizations/index.html",
            cms = "hand-rolled static",
            mid = "This approach incurs stalls.",
            last = "here are the objs resulting from a compilation of Quake with assembly optimizations",
            // The site has two templates in circulation; the older one heads its pages
            // with a caps wordmark and a link row instead of the one word.
            excludes = listOf("FABIEN SANGLARD'S WEBSITE", "DONATE"),
        ),
        ArticleFixture(
            slug = "fabiensanglard-silpheed",
            url = "$FS/silpheed/index.html",
            cms = "hand-rolled static",
            mid = "At its core the problem of FMV is a problem of bandwidth.",
            last = "artists had to design gameplay sequences using only twelve colors",
            excludes = listOf("FABIEN SANGLARD'S WEBSITE", "DONATE"),
        ),
    )

    /** The five gpuopen.com entries, whose feed ships a ~200-character `<description>`. */
    val excerptOnly: List<ArticleFixture> = listOf(
        ArticleFixture(
            slug = "gpuopen-adaptive-subdivision",
            url = "$GPU/gpu-view-adaptive-subdivision/",
            cms = "WordPress",
            mid = "The Entry node starts the process, dispatching one thread per base-patch.",
            last = "Browse the source code on GitHub from Coburg University.",
            excludes = AMD_CHROME,
        ),
        ArticleFixture(
            slug = "gpuopen-ray-tracing-animated-geometry",
            url = "$GPU/ray-tracing-massive-amounts-animated-geometry/",
            cms = "WordPress",
            mid = "This makes the approach especially attractive for massive scenes",
            last = "DirectX is a trademark of the Microsoft group of companies.",
            excludes = AMD_CHROME,
        ),
        ArticleFixture(
            slug = "gpuopen-gpu-crash-debugging-llms",
            url = "$GPU/post-mortem-gpu-crash-debugging-with-llms/",
            cms = "WordPress",
            mid = "Create a .vscode/mcp.json file in your workspace root",
            last = "The AMD RGD MCP Server bridges two previously separate worlds",
            excludes = AMD_CHROME,
        ),
        ArticleFixture(
            slug = "gpuopen-fsr-sdk-2-3",
            url = "$GPU/amd-fsr-sdk-2-3-blog/",
            cms = "WordPress",
            mid = "Make sure you visit our AMD FSR SDK home page here on GPUOpen.",
            last = "is a trademark or registered trademark of Epic Games, Inc.",
            excludes = AMD_CHROME,
        ),
        ArticleFixture(
            slug = "gpuopen-rdts-shader-source",
            url = "$GPU/radeon-developer-tool-suite-shader-source-code/",
            cms = "WordPress",
            mid = "Extended PIX Marker support in crash analysis output",
            last = "DirectX, Microsoft, and Windows are registered trademarks of Microsoft Corporation",
            excludes = AMD_CHROME,
        ),
    )

    /**
     * The ZDI page V09/#4 is about: a Squarespace post whose table *is* the post.
     *
     * The five `zdi-*.html` fixtures beside it are feed **bodies** and exercise lowering;
     * this is the **page**, so it exercises extraction. Squarespace wraps every block in
     * its own `sqs-block` div, which is what puts the table one sibling away from the
     * winning subtree and out of reach of a text-density sweep.
     */
    val squarespaceTable: ArticleFixture = ArticleFixture(
        slug = "zdi-page-june-2026-apple-update-review",
        url = "https://www.thezdi.com/blog/2026/6/30/the-june-2026-apple-security-update-review",
        cms = "Squarespace",
        mid = "The overwhelming majority (31 of 37) are WebKit/WebRTC bugs " +
            "reachable through malicious web content.",
        last = "Stay tuned for the regularly schedule Patch Tuesday blog covering Adobe and Microsoft.",
        excludes = listOf(
            "Stand at the front line of proactive security",
            "Submit a vulnerability",
            "Researcher Rewards",
        ),
    )

    /** Six more excerpt-only sources, chosen for CMS spread rather than for difficulty. */
    val other: List<ArticleFixture> = listOf(
        ArticleFixture(
            slug = "hexacorn-msconfig-secret",
            url = "https://www.hexacorn.com/blog/2026/06/07/little-secret-of-msconfig-exe/",
            cms = "WordPress",
            mid = "It turns out the program accepts less-known command line arguments",
            last = "Still, worth documenting.",
            excludes = listOf("Main menu", "Post navigation", "Case Studies"),
        ),
        ArticleFixture(
            slug = "robotwealth-triangulated-stat-arb",
            url = "https://robotwealth.com/resourcing-a-triangulated-stat-arb-operation-as-a-solo-trader/",
            cms = "WordPress",
            mid = "The signal is most informative in the tails.",
            last = "is what makes equity pairs worth trading as a solo operator",
            excludes = listOf(
                "Related Articles",
                "Latest Articles",
                "© Copyright 2026 Robot Wealth Pty Ltd",
                "Leave a Comment",
            ),
        ),
        ArticleFixture(
            slug = "doar-e-pwn2own-ics-2022-miami",
            url = "https://doar-e.github.io/blog/2023/05/05/competing-in-pwn2own-ics-2022-miami-" +
                "exploiting-a-zero-click-remote-memory-corruption-in-iconics-genesis64/",
            cms = "Pelican (static)",
            mid = "Once I identified the right memory primitives",
            last = "for proofreading this article",
            excludes = listOf("The theme is from Bootstrap from Twitter"),
        ),
        ArticleFixture(
            slug = "0xdf-htb-kobold",
            url = "https://0xdf.gitlab.io/2026/08/01/htb-kobold.html",
            cms = "Jekyll (static)",
            mid = "The presence of this template dropdown is itself a good sign",
            last = "ben could do the same privesc done through Arcane",
            excludes = listOf(
                "CTF solutions, malware analysis, home lab development",
                "Buy me a coffee",
                "Cheatsheets",
            ),
        ),
        ArticleFixture(
            slug = "ciechanow-moon",
            url = "https://ciechanow.ski/moon/",
            cms = "hand-rolled, WebGL-heavy",
            mid = "the angular momentum of the Moon had to be conserved",
            last = "Perhaps the next time you catch a glimpse of the Moon",
            excludes = listOf("Copyright © 2026 Bartosz Ciechanowski"),
        ),
        squarespaceTable,
    )

    val all: List<ArticleFixture> = noBody + excerptOnly + other

    /** The live gpuopen feed as harvested, so the ≥10× gate measures a real excerpt. */
    fun gpuopenFeed(): String = File(dir(), "gpuopen-feed.xml").readText()

    fun dir(): File = File(repoRoot(), "fixtures/articles")

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/articles").isDirectory) return dir
            dir = dir.parentFile
        }
        error("fixtures/articles not found")
    }
}
