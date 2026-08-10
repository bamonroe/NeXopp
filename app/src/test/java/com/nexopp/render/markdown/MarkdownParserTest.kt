package com.nexopp.render.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block structure a markdown import is laid out from. Every block type gets a case, and so do
 * the awkward ones — lazy continuation, containers holding other containers, CRLF and tabs — since
 * those are where a hand-written line parser goes wrong.
 */
class MarkdownParserTest {

    private fun parse(source: String) = MarkdownParser.parse(source.trimIndent())

    @Test fun `atx headings carry their level and drop the hashes`() {
        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "Title"),
                MarkdownBlock.Heading(3, "Deeper"),
                MarkdownBlock.Heading(2, "Closed"),
            ),
            parse(
                """
                # Title
                ### Deeper
                ## Closed ##
                """
            ),
        )
    }

    @Test fun `seven hashes and a bare hash run are not headings`() {
        assertEquals(listOf(MarkdownBlock.Paragraph("####### too deep")), parse("####### too deep"))
        assertEquals(listOf(MarkdownBlock.Paragraph("#nospace")), parse("#nospace"))
    }

    @Test fun `setext underlines turn the paragraph above into a heading`() {
        assertEquals(
            listOf(MarkdownBlock.Heading(1, "Title"), MarkdownBlock.Heading(2, "Subtitle")),
            parse(
                """
                Title
                ===

                Subtitle
                ---
                """
            ),
        )
    }

    @Test fun `paragraphs keep their soft line breaks and split on blank lines`() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("one\ntwo"), MarkdownBlock.Paragraph("three")),
            parse(
                """
                one
                two

                three
                """
            ),
        )
    }

    @Test fun `inline markup is left raw for the inline pass`() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("a **bold** and a [link](x)")),
            parse("a **bold** and a [link](x)"),
        )
    }

    @Test fun `fenced code keeps its body verbatim and records the language`() {
        val blocks = parse(
            """
            ```kotlin
            fun main() {
                println("hi")
            }
            ```
            """
        )
        assertEquals(
            listOf(MarkdownBlock.CodeBlock("fun main() {\n    println(\"hi\")\n}", "kotlin", fenced = true)),
            blocks,
        )
    }

    @Test fun `markup inside a fence is not parsed`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock("# not a heading\n- not a list", null, fenced = true)),
            parse(
                """
                ```
                # not a heading
                - not a list
                ```
                """
            ),
        )
    }

    @Test fun `an unclosed fence runs to the end of the document`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock("still code", null, fenced = true)),
            parse(
                """
                ```
                still code
                """
            ),
        )
    }

    @Test fun `a tilde fence closes only on tildes`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock("``` inside", null, fenced = true)),
            parse(
                """
                ~~~
                ``` inside
                ~~~
                """
            ),
        )
    }

    @Test fun `four space indentation is an indented code block`() {
        val blocks = MarkdownParser.parse("text\n\n    indented\n      more\n\nafter\n")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("text"),
                MarkdownBlock.CodeBlock("indented\n  more", null, fenced = false),
                MarkdownBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test fun `bullet lists collect their items`() {
        assertEquals(
            listOf(
                MarkdownBlock.ListBlock(
                    ordered = false,
                    items = listOf(
                        ListItem(listOf(MarkdownBlock.Paragraph("one"))),
                        ListItem(listOf(MarkdownBlock.Paragraph("two"))),
                    ),
                )
            ),
            parse(
                """
                - one
                - two
                """
            ),
        )
    }

    @Test fun `ordered lists record their starting number`() {
        val list = parse(
            """
            3. third
            4. fourth
            """
        ).single() as MarkdownBlock.ListBlock

        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test fun `an ordered list does not merge with the bullet list above it`() {
        val blocks = parse(
            """
            - bullet
            1. number
            """
        )
        assertEquals(2, blocks.size)
        assertEquals(false, (blocks[0] as MarkdownBlock.ListBlock).ordered)
        assertEquals(true, (blocks[1] as MarkdownBlock.ListBlock).ordered)
    }

    @Test fun `nested lists become lists inside their parent item`() {
        val outer = parse(
            """
            - one
              - inner a
              - inner b
            - two
            """
        ).single() as MarkdownBlock.ListBlock

        assertEquals(2, outer.items.size)
        val inner = outer.items[0].blocks[1] as MarkdownBlock.ListBlock
        assertEquals(MarkdownBlock.Paragraph("one"), outer.items[0].blocks[0])
        assertEquals(2, inner.items.size)
        assertEquals(MarkdownBlock.Paragraph("inner a"), inner.items[0].blocks.single())
    }

    @Test fun `a list item can contain a code block`() {
        val list = parse(
            """
            - here is code:

              ```
              value = 1
              ```

            - after
            """
        ).single() as MarkdownBlock.ListBlock

        assertEquals(2, list.items.size)
        assertEquals(
            MarkdownBlock.CodeBlock("value = 1", null, fenced = true),
            list.items[0].blocks[1],
        )
    }

    @Test fun `a list item continues lazily on an unindented line`() {
        val list = parse(
            """
            - first line
            continued here
            - second
            """
        ).single() as MarkdownBlock.ListBlock

        assertEquals(2, list.items.size)
        assertEquals(MarkdownBlock.Paragraph("first line\ncontinued here"), list.items[0].blocks.single())
    }

    @Test fun `block quotes parse their contents as blocks`() {
        assertEquals(
            listOf(
                MarkdownBlock.Quote(
                    listOf(MarkdownBlock.Heading(2, "quoted"), MarkdownBlock.Paragraph("body"))
                )
            ),
            parse(
                """
                > ## quoted
                >
                > body
                """
            ),
        )
    }

    @Test fun `quotes nest, and depth is the tree`() {
        val outer = parse(
            """
            > outer
            > > inner
            """
        ).single() as MarkdownBlock.Quote

        val inner = outer.blocks.last() as MarkdownBlock.Quote
        assertEquals(MarkdownBlock.Paragraph("outer"), outer.blocks.first())
        assertEquals(MarkdownBlock.Paragraph("inner"), inner.blocks.single())
    }

    @Test fun `a quote can contain a list`() {
        val quote = parse(
            """
            > - one
            > - two
            """
        ).single() as MarkdownBlock.Quote

        val list = quote.blocks.single() as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
    }

    @Test fun `a quote continues lazily onto an unmarked line`() {
        val quote = parse(
            """
            > quoted
            still quoted
            """
        ).single() as MarkdownBlock.Quote

        assertEquals(MarkdownBlock.Paragraph("quoted\nstill quoted"), quote.blocks.single())
    }

    @Test fun `thematic breaks are recognised in each spelling`() {
        assertEquals(
            listOf(MarkdownBlock.Rule, MarkdownBlock.Rule, MarkdownBlock.Rule, MarkdownBlock.Rule),
            parse(
                """
                ---

                ***

                ___

                - - -
                """
            ),
        )
    }

    @Test fun `a rule wins over a bullet list`() {
        assertEquals(listOf(MarkdownBlock.Rule), parse("* * *"))
    }

    @Test fun `crlf line endings parse the same as lf`() {
        assertEquals(
            MarkdownParser.parse("# Title\n\nbody\n"),
            MarkdownParser.parse("# Title\r\n\r\nbody\r\n"),
        )
    }

    @Test fun `tabs are expanded before indentation is counted`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock("tabbed", null, fenced = false)),
            MarkdownParser.parse("\ttabbed\n"),
        )
    }

    @Test fun `empty and blank input yield no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), MarkdownParser.parse(""))
        assertEquals(emptyList<MarkdownBlock>(), MarkdownParser.parse("\n   \n\n"))
    }

    @Test fun `a whole document parses into its blocks in order`() {
        val blocks = parse(
            """
            # Notes

            Some prose about *things*.

            ## List

            - alpha
            - beta

            > a remark

            ---

            Done.
            """
        )
        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "Notes"),
                MarkdownBlock.Paragraph("Some prose about *things*."),
                MarkdownBlock.Heading(2, "List"),
                MarkdownBlock.ListBlock(
                    ordered = false,
                    items = listOf(
                        ListItem(listOf(MarkdownBlock.Paragraph("alpha"))),
                        ListItem(listOf(MarkdownBlock.Paragraph("beta"))),
                    ),
                ),
                MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph("a remark"))),
                MarkdownBlock.Rule,
                MarkdownBlock.Paragraph("Done."),
            ),
            blocks,
        )
    }
}
