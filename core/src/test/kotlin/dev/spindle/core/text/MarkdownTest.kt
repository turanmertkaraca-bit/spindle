package dev.spindle.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The markdown parser is pure, so every block and inline shape it promises is
 * pinned here on a plain JVM: headings, inline spans, lists, fences (with and
 * without an info string, closed and unclosed) and links.
 */
class MarkdownTest {

    private fun paragraphText(block: MdBlock): String =
        (block as MdBlock.Paragraph).spans.joinToString("") { it.text }

    @Test
    fun `parses atx headings at each level`() {
        val blocks = Markdown.parse("# one\n## two\n### three\n###### six")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "one"),
                MdBlock.Heading(2, "two"),
                MdBlock.Heading(3, "three"),
                MdBlock.Heading(6, "six"),
            ),
            blocks,
        )
    }

    @Test
    fun `strips closing hashes and tolerates no space after them`() {
        assertEquals(listOf(MdBlock.Heading(2, "hello")), Markdown.parse("##hello##"))
        assertEquals(listOf(MdBlock.Heading(1, "title")), Markdown.parse("# title #"))
    }

    @Test
    fun `seven hashes is not a heading`() {
        val blocks = Markdown.parse("####### nope")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `parses indented headings and keeps a legitimate trailing hash`() {
        assertEquals(listOf(MdBlock.Heading(1, "indented")), Markdown.parse("   # indented"))
        assertEquals(listOf(MdBlock.Heading(1, "C#")), Markdown.parse("# C#"))
        assertEquals(listOf(MdBlock.Heading(1, "title")), Markdown.parse("# title #"))
        assertTrue(Markdown.parse("    # too deep").single() is MdBlock.Paragraph)
    }

    @Test
    fun `parses bold italic and inline code spans`() {
        val block = Markdown.parse("a **bold** b *italic* c _also_ d `code` e").single()
        val spans = (block as MdBlock.Paragraph).spans
        assertEquals(
            listOf(
                MdSpan("a "),
                MdSpan("bold", bold = true),
                MdSpan(" b "),
                MdSpan("italic", italic = true),
                MdSpan(" c "),
                MdSpan("also", italic = true),
                MdSpan(" d "),
                MdSpan("code", code = true),
                MdSpan(" e"),
            ),
            spans,
        )
    }

    @Test
    fun `parses double underscore bold`() {
        val spans = (Markdown.parse("x __strong__ y").single() as MdBlock.Paragraph).spans
        assertEquals(listOf(MdSpan("x "), MdSpan("strong", bold = true), MdSpan(" y")), spans)
    }

    @Test
    fun `parses a link with its url`() {
        val spans = (Markdown.parse("see [docs](https://example.com/a) now").single() as MdBlock.Paragraph).spans
        assertEquals(
            listOf(MdSpan("see "), MdSpan("docs", linkUrl = "https://example.com/a"), MdSpan(" now")),
            spans,
        )
    }

    @Test
    fun `keeps emphasis inside link text`() {
        val spans = (Markdown.parse("[**bold** link](u)").single() as MdBlock.Paragraph).spans
        assertEquals(listOf(MdSpan("bold", bold = true, linkUrl = "u"), MdSpan(" link", linkUrl = "u")), spans)
    }

    @Test
    fun `parses bullet lists with mixed markers`() {
        val block = Markdown.parse("- one\n* two\n+ three").single()
        assertEquals(
            MdBlock.Bullet(
                listOf(
                    listOf(MdSpan("one")),
                    listOf(MdSpan("two")),
                    listOf(MdSpan("three")),
                ),
            ),
            block,
        )
    }

    @Test
    fun `parses ordered lists with dot and paren markers`() {
        val block = Markdown.parse("1. first\n2) second").single()
        assertEquals(
            MdBlock.Ordered(
                listOf(
                    listOf(MdSpan("first")),
                    listOf(MdSpan("second")),
                ),
            ),
            block,
        )
    }

    @Test
    fun `list items carry inline spans`() {
        val block = Markdown.parse("- **hot** item").single() as MdBlock.Bullet
        assertEquals(listOf(listOf(MdSpan("hot", bold = true), MdSpan(" item"))), block.items)
    }

    @Test
    fun `parses a fenced code block without a language`() {
        val block = Markdown.parse("```\nval x = 1\nprintln(x)\n```").single()
        assertEquals(MdBlock.Code(null, "val x = 1\nprintln(x)"), block)
    }

    @Test
    fun `parses a fenced code block with a language`() {
        val block = Markdown.parse("```kotlin\nval x = 1\n```").single()
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), block)
    }

    @Test
    fun `parses tilde fences and keeps fence markers out of the body`() {
        val block = Markdown.parse("~~~python\nprint('hi')\n~~~").single()
        assertEquals(MdBlock.Code("python", "print('hi')"), block)
    }

    @Test
    fun `a shorter fence does not close a longer one`() {
        val block = Markdown.parse("````\ncode\n```\nmore\n````").single()
        assertEquals(MdBlock.Code(null, "code\n```\nmore"), block)
    }

    @Test
    fun `an unclosed fence renders the rest of the input as code`() {
        val block = Markdown.parse("before\n\n```\nstill code\nmore code").last()
        assertEquals(MdBlock.Code(null, "still code\nmore code"), block)
    }

    @Test
    fun `code fences are not scanned for inline markers`() {
        val block = Markdown.parse("```\n**not bold** `not code`\n```").single() as MdBlock.Code
        assertEquals("**not bold** `not code`", block.text)
    }

    @Test
    fun `malformed inline markers pass through literally`() {
        assertEquals(listOf(MdSpan("a ** b")), (Markdown.parse("a ** b").single() as MdBlock.Paragraph).spans)
        assertEquals(listOf(MdSpan("2 * 3 * 4")), (Markdown.parse("2 * 3 * 4").single() as MdBlock.Paragraph).spans)
        assertEquals(listOf(MdSpan("snake_case")), (Markdown.parse("snake_case").single() as MdBlock.Paragraph).spans)
        assertEquals(listOf(MdSpan("a ` b")), (Markdown.parse("a ` b").single() as MdBlock.Paragraph).spans)
    }

    @Test
    fun `blank lines split paragraphs and preserve inner line breaks`() {
        val blocks = Markdown.parse("first\nsecond\n\nthird")
        assertEquals(2, blocks.size)
        assertEquals("first\nsecond", paragraphText(blocks[0]))
        assertEquals("third", paragraphText(blocks[1]))
    }

    @Test
    fun `a heading followed by a paragraph parses as two blocks`() {
        assertEquals(
            listOf(MdBlock.Heading(1, "Title"), MdBlock.Paragraph(listOf(MdSpan("body")))),
            Markdown.parse("# Title\nbody"),
        )
    }

    @Test
    fun `a realistic mixed document parses in order`() {
        val src = """
            ## Fix

            The bug is in **StepMapper**.

            - read the file
            - apply the edit

            ```kotlin
            val open = false
            ```
        """.trimIndent()
        val blocks = Markdown.parse(src)
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.Bullet)
        assertTrue(blocks[3] is MdBlock.Code)
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(Markdown.parse("").isEmpty())
    }

    @Test
    fun `parsing is deterministic`() {
        val src = "# h\n\n- a\n- b\n\n`x` and **y**"
        assertEquals(Markdown.parse(src), Markdown.parse(src))
    }
}
