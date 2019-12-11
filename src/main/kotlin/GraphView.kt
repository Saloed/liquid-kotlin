import info.leadinglight.jdot.Edge
import info.leadinglight.jdot.Graph
import info.leadinglight.jdot.Node
import info.leadinglight.jdot.enums.Shape
import org.jetbrains.research.kfg.util.simpleHash
import java.io.File
import java.nio.file.Files
import info.leadinglight.jdot.impl.Util
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.ValueTerm

class GraphView(
        val name: String,
        val label: String,
        val successors: MutableList<GraphView>
) {
    constructor(name: String, label: String) : this(name, label, mutableListOf())

    override fun hashCode() = simpleHash(name)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (this.javaClass != other?.javaClass) return false
        other as GraphView
        return this.name == other.name
    }

    override fun toString() = "[$name]: $label {${successors.map { it.name }}}"
}

fun liquidTypesToGraphView(types: Iterable<LiquidType>): Collection<GraphView> {
    val mapping = types.zipMap { GraphView(it.variable.name, "${it.variable.name}:${it.expression.text}") }.toMap()
    for (type in types) {
        val view = mapping[type]!!
        type.dependsOn.map { mapping[it]!! }.forEach {
            view.successors.add(it)
        }
    }
    return mapping.values
}


fun basicPsToGraphView(name: String, ps: BasicState) {
    val predicates = ps.predicates
    val predicateWithDependencies = predicates.zipMap { it.operands }.toMap()
    val termsWithDependencies = HashMap<Term, List<Term>>()

    for (term in predicates.flatMap { it.operands }.flatMap { it.collectDescendantsOfType<Term> { true } }) {
        termsWithDependencies.computeIfAbsent(term) {
            it.subterms
        }
    }

    val uniqueTerms = termsWithDependencies.flatMap { it.value + listOf(it.key) }.toSet()
    val termNodes = uniqueTerms.zipMap { GraphView("Term_${it.hashCode()}", "${it::class.simpleName}:  ${it.name} | ${it.name}") }.toMap()
    for ((term, deps) in termsWithDependencies) {
        val node = termNodes[term]!!
        node.successors.addAll(deps.mapNotNull { termNodes[it] }.filterNot { it in node.successors })
    }

    val uniquePredicates = predicateWithDependencies.keys
    val predicatesNodes = uniquePredicates.zipMap { GraphView("Predicate_${it.hashCode()}", "Predicate:| $it") }.toMap()
    for ((pred, deps) in predicateWithDependencies) {
        predicatesNodes[pred]?.successors?.addAll(deps.mapNotNull { termNodes[it] })
    }

    val nodes = termNodes.values + predicatesNodes.values
    viewGraph(name, nodes)
}

fun psToGraphView(name: String, ps: PredicateState) = when (ps) {
    is BasicState -> basicPsToGraphView(name, ps)
    else -> throw IllegalArgumentException("No graph view for ${ps::class.simpleName}")
}

fun viewLiquidTypes(name: String, types: Iterable<LiquidType>) {
    val graph = liquidTypesToGraphView(types)
    viewGraph(name, graph.toList())
}

fun fixLabel(label:String) = label.replace("\"", "\\\"")

fun viewGraph(name: String, nodes: List<GraphView>, dot: String = "/usr/bin/dot", browser: String = "/usr/bin/chromium") {
    Graph.setDefaultCmd(dot)
    val graph = Graph(name)
    graph.addNodes(*nodes.map {
        Node(it.name).setShape(Shape.box).setLabel(fixLabel(it.label)).setFontSize(12.0)
    }.toSet().toTypedArray())

    nodes.forEach {
        for (succ in it.successors) {
            graph.addEdge(Edge(succ.name, it.name))
        }
    }
    val file = graph.dot2file("svg")
    val newFile = "${file.removeSuffix("out")}svg"
    Files.move(File(file).toPath(), File(newFile).toPath())
    Util.sh(arrayOf(browser).plus("file://$newFile"))
}
