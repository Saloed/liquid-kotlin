package fixpoint

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.jetbrains.research.fixpoint.LiquidFixpointLexer
import org.jetbrains.research.fixpoint.LiquidFixpointParser
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger


object Solver {

    sealed class Result(val output: String, val error: String) {
        class Success(output: String, error: String) : Result(output, error)
        class Error(output: String, error: String) : Result(output, error)
    }

    private const val workDirPath = "solverQueries"
    private val workDir = File(workDirPath).also {
        it.mkdirs()
    }

    init {
        workDir.walkTopDown()
                .filterNot { it == workDir }
                .filter { it.exists() }
                .forEach { it.deleteRecursively() }
    }

    private val solutionsDir = File(workDir, ".liquid")

    private fun getMaxQueryId(): Int {
        val queries = workDir.listFiles().filter { it.isFile }.toList()
        val lastQuery = queries.map { it.nameWithoutExtension }.map { Integer.valueOf(it) }.maxBy { it }
        return lastQuery ?: 0
    }

    private val queryId by lazy { AtomicInteger(getMaxQueryId()) }

    private fun runSolver(file: File): Result {
        val process = ProcessBuilder("fixpoint", "--save", "--minimalsol", "--defunction", "--allowho", "--eliminate=none", file.name)
                .directory(workDir)
                .start()
        process.waitFor()
        val output = process.inputStream.reader().readText()
        val error = process.errorStream.reader().readText()
        println(output)
        println(error)
        val errorPattern = Regex("Oops, unexpected error:.*Error")
        return when {
            output.contains(errorPattern) -> Solver.Result.Error(output, error)
            else -> Solver.Result.Success(output, error)
        }
    }

    fun solve(query: Query): List<Solution> {
        val queryName = "${queryId.getAndIncrement()}.fq"
        val queryFile = File(workDir, queryName)
        writeQuery(queryFile.absolutePath, query)
        println("Run solver for $queryName")
        val status = runSolver(queryFile)
        println("Finish for $queryName")
        if (status !is Result.Success) return emptyList()
        val solutionFile = File(solutionsDir, "$queryName.fqout")
        return readSolutions(solutionFile.absolutePath)
    }

    private fun writeQuery(fileName: String, query: Query) {
        val queryStr = query.print()
        File(fileName).writeText(queryStr)
    }

    fun readSolutions(fileName: String): List<Solution> {
        val charStream = CharStreams.fromFileName(fileName, Charset.forName("UTF-8"))
        val lexer = LiquidFixpointLexer(charStream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = LiquidFixpointParser(tokenStream)
        val file = parser.solution_file()
        return SolutionFileVisitor().visit(file)
    }

}
