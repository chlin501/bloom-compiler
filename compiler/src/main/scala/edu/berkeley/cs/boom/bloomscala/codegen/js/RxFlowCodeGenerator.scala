package edu.berkeley.cs.boom.bloomscala.codegen.js

import edu.berkeley.cs.boom.bloomscala.codegen.dataflow._
import edu.berkeley.cs.boom.bloomscala.codegen.dataflow.HashEquiJoinElement
import edu.berkeley.cs.boom.bloomscala.codegen.dataflow.Table
import edu.berkeley.cs.boom.bloomscala.parser.BloomPrettyPrinter
import edu.berkeley.cs.boom.bloomscala.ast.RowExpr

/**
 * Compiles Bloom programs to Javascript that use the RxJs and RxFlow libraries.
 *
 * The output contains a Javascript object that exposes Bloom collections as
 * objects that implement the Rx Observable and Observer interfaces.
 *
 * myObj.collectionNameIn is an Observer that can be used to push new tuples
 * into the system by calling myObj.collectionNameIn.onNext(tuple).
 *
 * myObj.collectionNameOut is a Subject that can be subscribed() to
 * in order to receive callbacks when new tuples are added to collections.
 */
object RxFlowCodeGenerator extends DataflowCodeGenerator with JsCodeGeneratorUtils {

  private def elemName(elem: DataflowElement): Doc = {
    elem match {
      case Table(collection) => dquotes(collection.name)
      case InputElement(collection) => dquotes(collection.name)
      case OutputElement(collection) => dquotes(collection.name)
      case e => text(e.id.toString)
    }
  }

  private def elemRef(elem: DataflowElement): Doc = {
    val mapName = elem match {
      case t: Table => "tables"
      case i: InputElement => "inputs"
      case o: OutputElement => "outputs"
      case _ => "elements"
    }
    mapName <> brackets(elemName(elem))
  }

  private def portName(inputPort: InputPort): Doc = {
    elemRef(inputPort.elem) <> (inputPort.elem match {
      case HashEquiJoinElement(_, _, _) =>
        inputPort.name match {
          case "leftInput" => ".leftInput"
          case "rightInput" => ".rightInput"
        }
      case Table(collection) =>
        inputPort.name match {
          case "deltaIn" => ".insert"
        }
      case _ => ".input"
    })
  }

  private def portName(outputPort: OutputPort): Doc = {
    elemRef(outputPort.elem) <> dot <> "output"
  }

  private def buildInvalidationAndRescanLookupTables(graph: DataflowGraph): Doc = {
    val invalidations = graph.invalidationLookupTable.map { case (k, v) =>
      (elemName(k), arrayLiteral(v.toSeq.sortBy(_.id).map(elemRef)))
    }
    val rescans = graph.rescanLookupTable.map { case (k, v) =>
      (elemName(k), arrayLiteral(v.toSeq.sortBy(_.id).map(elemRef)))
    }
    "var" <+> "invalidationLookupTable" <+> equal <+> mapLiteral(invalidations) <> semi <@@> line <>
    "var" <+> "rescanLookupTable" <+> equal <+> mapLiteral(rescans) <> semi
  }

  private def buildTables(graph: DataflowGraph): Doc = {
    val tables = graph.tables.values.map { table =>
      (elemName(table), "new" <+> methodCall("rxflow", "Table", table.lastKeyColIndex.toString) <+>
        comment(BloomPrettyPrinter.pretty(table.collection)))
    }.toMap

    "var" <+> "tables" <+> equal <+> mapLiteral(tables) <> semi
  }

  private def buildInputs(graph: DataflowGraph): Doc = {
    // TODO: this should create an Rx observer, not a Subject.
    val inputs = graph.inputs.values.map { input =>
      (elemName(input), "new" <+> methodCall("rx", "Subject") <+>
        comment(BloomPrettyPrinter.pretty(input.collection)))
    }.toMap

    "var" <+> "inputs" <+> equal <+> mapLiteral(inputs) <> semi <@@>
    graph.inputs.values.map { input =>
      "this" <> dot <> input.collection.name <+> equal <+> elemRef(input) <> semi
    }.foldLeft(empty)(_ <@@> _)
  }

  private def buildOutputs(graph: DataflowGraph): Doc = {
    val outputs = graph.outputs.values.map { output =>
      (elemName(output), "new" <+> methodCall("rxflow", "ObservableSink") <+>
        comment(BloomPrettyPrinter.pretty(output.collection)))
    }.toMap

    "var" <+> "outputs" <+> equal <+> mapLiteral(outputs) <> semi <@@>
      graph.outputs.values.map { output =>
        "this" <> dot <> output.collection.name <+> equal <+> elemRef(output) <> dot <> "output" <> semi
      }.foldLeft(empty)(_ <@@> _)
  }

  private def buildElement(elem: DataflowElement): Doc = {
    elem match {
      case hj @ HashEquiJoinElement(buildKey, probeKey, leftIsBuild) =>
        "new" <+> methodCall("rxflow", "HashJoin",
            genLambda(buildKey, List("x")),
            genLambda(probeKey, List("x")),
            if (leftIsBuild) "\"left\"" else "\"right\""
          )
      case mapElem @ MapElement(mapFunction, functionArity) =>
        val exprParameterNames =
          if (functionArity == 1) List("x")
          else (0 to functionArity - 1).map(x => s"x[$x]").toList
        "new" <+> methodCall("rxflow", "Map", genLambda(mapFunction, List("x"), Some(exprParameterNames)))
      case Scanner(scannableElem) =>
        scannableElem match {
          case table: Table =>
            "new" <+> methodCall("rxflow", "TableScanner", elemRef(table))
          case input: InputElement =>
            "new" <+> methodCall("rxflow", "ObservableScanner", elemRef(input))
        }
      case ArgMinElement(groupingCols, chooseExpr, orderingFunction) =>
        "new" <+> methodCall("rxflow", "ArgMin", genLambda(RowExpr(groupingCols), List("x")), genLambda(chooseExpr, List("x")),
          "function(x, y) { return x <= y; }")
    }
  }

  private def buildElements(graph: DataflowGraph): Doc = {
    // TODO: handle strata
    val elements = graph.stratifiedElements.flatMap(_._2)
      .filterNot(_.isInstanceOf[ScannableDataflowElement])
      .filterNot(_.isInstanceOf[OutputElement]).toSeq
    val elementsMap = elements.map { elem =>
      (elemName(elem), buildElement(elem))
    }.toMap

    "var" <+> "elements" <+> equal <+> mapLiteral(elementsMap) <> semi
  }

  private def wireElements(graph: DataflowGraph): Doc = {
    val elements = graph.stratifiedElements.flatMap(_._2.filterNot(_.isInstanceOf[Table]).toSeq)
    elements.sortBy(_.id).flatMap { elem =>
      elem.outputPorts.toSeq.sortBy(_.name).flatMap { outputPort =>
        outputPort.connections.toSeq.sortBy(_.to.elem.id).map { case Edge(_, inputPort) =>
          portName(outputPort) <> dot <> functionCall("subscribe", portName(inputPort)) <> semi
      }}
    }.reduce(_ <@@> _)
  }

  private def buildTickFunction(graph: DataflowGraph): Doc = {
    val inputScanners = graph.elements.collect { case is @ Scanner(i: InputElement) => is }
    val tables = graph.tables.values
    "this.tick" <+> equal <+>  "function()" <+> braces(nest(
      inputScanners.map(ie => methodCall(elemRef(ie), "endRound", "currentTick") <> semi).foldLeft(empty)(_ <@@> _) <>
      tables.map(ie => methodCall(elemRef(ie), "endRound", "currentTick") <> semi).foldLeft(empty)(_ <@@> _) <@@>
      "currentTick += 1;"
    ) <> linebreak)
  }

  override def generateCode(graph: DataflowGraph): CharSequence = {

    val code = "function Bloom ()" <+> braces(nest(
      linebreak <>
      "var rx = require('rx');" <@@>
      "var rxflow = require('rxflow');" <@@>
      "var currentTick = 0;" <@@>
      linebreak <>
      buildTables(graph) <@@>
      linebreak <>
      buildInputs(graph) <@@>
      linebreak <>
      buildOutputs(graph) <@@>
      linebreak <>
      buildElements(graph) <@@>
      linebreak <>
      buildInvalidationAndRescanLookupTables(graph) <@@>
      linebreak <>
      wireElements(graph) <@@>
      linebreak <>
      buildTickFunction(graph)
    ) <> linebreak)
    super.pretty(code)
  }

}
