package fixpoint

import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.jetbrains.research.fixpoint.LiquidFixpointLexer
import org.jetbrains.research.fixpoint.LiquidFixpointParser
import org.jetbrains.research.fixpoint.LiquidFixpointParserBaseListener
import java.io.File


object QueryMinimizer {


    class ItRenaming(val rewriter: TokenStreamRewriter) : LiquidFixpointParserBaseListener() {
        private var nameToReplace: String? = null
        private var skip = false

        override fun enterBind_constraint(ctx: LiquidFixpointParser.Bind_constraintContext) {
            if (skip) return
            val name = ctx.IDENTIFIER()
            nameToReplace = name.text
            rewriter.replace(name.symbol, "it")
        }


        override fun enterExpression(ctx: LiquidFixpointParser.ExpressionContext) {
            if (nameToReplace == null) return
            ctx.IDENTIFIER().forEach {
                if (it.text == nameToReplace) {
                    rewriter.replace(it.symbol, "it")
                }
            }
        }

        override fun enterSubstitution(ctx: LiquidFixpointParser.SubstitutionContext) {
            if (nameToReplace == null) return
            val name = ctx.IDENTIFIER(1)
            if (name.text == nameToReplace) {
                rewriter.replace(name.symbol, "it")
            }
        }

        override fun exitBind_constraint(ctx: LiquidFixpointParser.Bind_constraintContext) {
            nameToReplace = null
        }

        override fun enterWf_statement(ctx: LiquidFixpointParser.Wf_statementContext) {
            skip = true
        }

        override fun exitWf_statement(ctx: LiquidFixpointParser.Wf_statementContext) {
            skip = false
        }
    }


    class DuplicatePredicatesRemover(val rewriter: TokenStreamRewriter) : LiquidFixpointParserBaseListener() {
        override fun enterBind_predicates(ctx: LiquidFixpointParser.Bind_predicatesContext) {
            val predicates = ctx.bind_predicate()
            val delimeters = ctx.SEMI()
            val knownPredicates = mutableSetOf<String>()

            rewriter.deleteElementsFromList(predicates, delimeters) { predicate ->
                val text = predicate.text
                if (text !in knownPredicates) {
                    knownPredicates.add(text)
                    false
                } else {
                    true
                }
            }
        }
    }

    class UsagesCollector : LiquidFixpointParserBaseListener() {
        val identifiers = mutableSetOf<String>()
        override fun enterExpression(ctx: LiquidFixpointParser.ExpressionContext) {
            identifiers.addAll(ctx.IDENTIFIER().map { it.text })
        }

        override fun enterSubstitution(ctx: LiquidFixpointParser.SubstitutionContext) {
            identifiers.addAll(ctx.IDENTIFIER().map { it.text })
        }

    }

    class UnusedBindsRemover(val rewriter: TokenStreamRewriter, val usedIdentifiers: Set<String>) : LiquidFixpointParserBaseListener() {
        val removedBinds = mutableSetOf<Int>()
        override fun enterBind_statement(ctx: LiquidFixpointParser.Bind_statementContext) {
            val name = ctx.identifier_or_func().text
            if (name in usedIdentifiers) return
            val idx = ctx.DECIMAL().text.toInt()
            removedBinds.add(idx)
            rewriter.delete(ctx.start, ctx.stop)
        }

        override fun enterConstant_statement(ctx: LiquidFixpointParser.Constant_statementContext) {
            val name = ctx.IDENTIFIER().text
            if (name in usedIdentifiers) return
            rewriter.delete(ctx.start, ctx.stop)
        }
    }


    class FixConstraintBindList(val rewriter: TokenStreamRewriter, val removedBinds: Set<Int>) : LiquidFixpointParserBaseListener() {
        override fun enterConstraint_statement_env(ctx: LiquidFixpointParser.Constraint_statement_envContext) {
            rewriter.deleteElementsFromList(ctx.DECIMAL(), ctx.SEMI()) {
                val idx = it.text.toInt()
                idx in removedBinds
            }
        }
    }

    fun minimize(fileName: String) {
        val source = File(fileName).readText()
        var result = source.parseAndRewrite { walk { DuplicatePredicatesRemover(it) } }
        while (true) {
            val newResult = result.parseAndRewrite {
                val usedIdentifiers = walk { UsagesCollector() }.identifiers
                val removedBinds = walk { UnusedBindsRemover(it, usedIdentifiers) }.removedBinds
                walk { FixConstraintBindList(it, removedBinds) }
            }
            if (newResult == result) break
            result = newResult
        }
        result = result.parseAndRewrite { walk { ItRenaming(it) } }
        val manySpacePattern = Regex("(\\s+\\n)+")
        val beautyResult = result.replace(manySpacePattern, "\n")
        File("$fileName.new").writeText(beautyResult)
    }


    private fun String.parseAndRewrite(modify: ParseTree.(TokenStreamRewriter) -> Unit): String {
        val charStream = CharStreams.fromString(this)
        val lexer = LiquidFixpointLexer(charStream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = LiquidFixpointParser(tokenStream)
        val rewriter = TokenStreamRewriter(tokenStream)
        val file = parser.query_file()
        file.modify(rewriter)
        return rewriter.text
    }

    private inline fun <reified T : ParseTree> TokenStreamRewriter.deleteElementsFromList(elements: List<T>, delimeters: List<TerminalNode>, predicate: (T) -> Boolean) {
        val deletedDelimIdx = mutableListOf<Int>()
        for ((idx, element) in elements.withIndex()) {
            if (!predicate(element)) continue
            when (element) {
                is ParserRuleContext -> delete(element.start, element.stop)
                is TerminalNode -> delete(element.symbol)
                else -> throw IllegalArgumentException("Unexpected element type: $element")
            }
            if (idx != elements.lastIndex) {
                delete(delimeters[idx].symbol)
                deletedDelimIdx.add(idx)
            } else {
                val actualDelimeters = delimeters.withIndex()
                        .filter { (idx, _) -> idx !in deletedDelimIdx }
                        .map { (_, delim) -> delim }
                if (actualDelimeters.isEmpty()) continue
                delete(actualDelimeters.last().symbol)
            }
        }
    }

    private inline fun <reified T : ParseTreeListener> ParseTree.walk(walker: () -> T): T {
        val walkerInstance = walker()
        ParseTreeWalker().walk(walkerInstance, this)
        return walkerInstance
    }
}


fun main() {
//    val name = "/storage/sobol/tmp/liquid-fixpoint/test_mutable_field.fq"
//    val name = "/storage/sobol/tmp/liquid-fixpoint/test.fq"
//    val name = "/storage/sobol/tmp/refscript/.liquid/mytest2.ts.fq"
    val name = "/storage/sobol/IdeaProjects/LiquidTypes/solverQueries/151.fq"

    QueryMinimizer.minimize(name)
}
