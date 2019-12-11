import org.jetbrains.research.kex.config.FileConfig
import org.jetbrains.research.kex.config.GlobalConfig
import org.jetbrains.research.kex.config.RuntimeConfig
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.smt.Result
import org.jetbrains.research.kex.smt.SMTProxySolver
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kfg.ClassManager
import kotlin.random.Random

object DeltaDebugger {

    val random = Random(17)

    fun debug(ps: PredicateState, query: PredicateState): PredicateState {
        kexConfig.initialize(RuntimeConfig, FileConfig("kex/kex.ini"))
        val solver = SMTProxySolver(ClassManager(emptyMap()).type)

        var result = ps
        for (i in 0..1000) {
            result = deltaDebug(result, query, solver)
        }
        return result
    }

    fun removePredicate(removeIdx: Int, ps: PredicateState): PredicateState {
        var idx = 0
        return ps.filter {
            val remove = idx != removeIdx
            idx++
            remove
        }.simplify()
    }

    fun deltaDebug(ps: PredicateState, query: PredicateState, solver: SMTProxySolver): PredicateState {
        val removeIdx = random.nextInt(ps.size)
        val psWithoutPredicate = removePredicate(removeIdx, ps)
        val solverResult = solver.isViolated(psWithoutPredicate, query)
        if (solverResult is Result.SatResult) return ps
        if (solverResult is Result.UnknownResult) return psWithoutPredicate
        return psWithoutPredicate
    }
}

