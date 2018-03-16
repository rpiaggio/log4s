package org.log4s
package log4sjs

import scala.annotation.tailrec
import scala.collection.{ breakOut, mutable, immutable }
import scala.math.Ordered._

import scala.scalajs.js
import js.annotation._

case class AppenderSetting(appenders: immutable.Seq[Log4sAppender.Type], additive: Boolean)

object Log4sConfig { thisConfig =>
  private[this] lazy val standardAppender = new Log4sConsoleAppender

  private[this] lazy val defaultAppenderSetting = AppenderSetting(Nil, true)

  private[this] case class ConcreteLoggerState(threshold: LogThreshold, appenders: Iterable[Log4sAppender]) {
    def withChild(ls: LoggerState): ConcreteLoggerState = {
      val newThreshold = ls.threshold.getOrElse(threshold)
      val newAppenders = {
        val AppenderSetting(childAppenders, childAdditive) = ls.appenders.getOrElse(defaultAppenderSetting)
        if (childAdditive) {
          appenders ++ childAppenders
        } else {
          childAppenders
        }
      }
      ConcreteLoggerState(newThreshold, newAppenders)
    }

    def isEnabled(ll: LogLevel): Boolean = {
      threshold <= LevelThreshold(ll)
    }
  }

  private[this] case class LoggerState(threshold: Option[LogThreshold] = None, appenders: Option[AppenderSetting] = None)

  private[this] lazy val emptyLoggerState = LoggerState()

  private[this] class Node(
      val children: mutable.Map[String, Node] = mutable.Map.empty,
      var state: LoggerState = emptyLoggerState)

  private[this] object LoggerState {
    private[this] val defaultRootState = ConcreteLoggerState(AllThreshold, Seq(standardAppender))
    private[this] val root = new Node()

    def apply(parts: Seq[String]): ConcreteLoggerState = {
      @inline
      @tailrec
      def helper(tree: collection.Map[String, Node], state: ConcreteLoggerState, path: Seq[String]): ConcreteLoggerState = {
        if (path.isEmpty) {
          state
        } else {
          tree.get(path.head) match {
            case None    => state
            case Some(n) => helper(n.children, state.withChild(n.state), path.tail)
          }
        }
      }
      helper(root.children, defaultRootState.withChild(root.state), parts)
    }

    @tailrec
    protected[this] def getNode(parts: Seq[String], node: Node = root): Option[Node] = {
      /* This method can be written in one line as a left fold that does a `return None` in a
       * `getOrElse`. However the early return is unusual and it may not perform very well to
       * throw an exception in the most common case, so the tail-recursive form is fine. */

      if (parts.isEmpty) {
        Some(node)
      } else {
        node.children.get(parts.head) match {
          case None     => None
          case Some(n2) => getNode(parts.tail, n2)
        }
      }
    }

    def get(parts: Seq[String]): LoggerState = {
      getNode(parts)
        .map(_.state)
        .getOrElse(emptyLoggerState)
    }

    def update(parts: Seq[String], state: LoggerState): Unit = {
      val pathNode =
        parts.foldLeft(root) { (node, part) =>
          node.children.getOrElseUpdate(part, new Node())
        }

      /* TODO: Merge settings rather than pure overwrite? */
      pathNode.state = state
    }
  }

  def logger(name: String, threshold: Option[Option[LogThreshold]] = None, appenders: Option[Option[AppenderSetting]] = None): Unit = {
    val parts = CategoryParser(name)
    val currentState = LoggerState.get(parts)
    var updatedState = currentState
    for (t <- threshold) {
      updatedState = updatedState.copy(threshold = t)
    }
    for (a <- appenders) {
      updatedState = updatedState.copy(appenders = a)
    }
    LoggerState.update(parts, updatedState)
  }

  @JSExportTopLevel("Config.setLoggerThreshold")
  def setLoggerThreshold(name: String, threshold: LogThreshold): Unit = {
    logger(name, threshold = Some(Option(threshold)))
  }

  @JSExportTopLevel("Config.setLoggerThreshold")
  def setLoggerThreshold(name: String, level: LogLevel): Unit = {
    logger(name, threshold = Some(Option(LevelThreshold(level))))
  }

  @JSExportTopLevel("Config.setLoggerThreshold")
  def setLoggerThreshold(name: String, threshold: String): Unit = {
    setLoggerThreshold(name, LogThreshold.forName(threshold))
  }

  @JSExportTopLevel("Config.setCategoryAppenders")
  def setCategoryAppendersDynamic(name: String, additive: Boolean, appenders: js.Array[Log4sAppender.DynamicType]): Unit = {
    setCategoryAppenders(name, additive, appenders)
  }

  def setCategoryAppenders[A: Log4sAppender.Provider](name: String, additive: Boolean, appenders: Seq[A]): Unit = {
    val appenderSeq: immutable.Seq[Log4sAppender] = appenders.map(Log4sAppender.from(_))(breakOut)
    logger(name, appenders = Some(Some(AppenderSetting(appenderSeq, additive))))
  }

  /** Add an appender for a given category */
  @JSExportTopLevel("Config.addCategoryAppender")
  def addCategoryAppenderDynamic(name: String, appender: Log4sAppender.DynamicType): Unit = {
    addCategoryAppender(name, appender)
  }

  def addCategoryAppender[A: Log4sAppender.Provider](name: String, appender: A): Unit = {
    val parts = CategoryParser(name)
    val currentState = LoggerState.get(parts)
    val currentAppenderSetting = currentState.appenders.getOrElse(AppenderSetting(Nil, true))
    val newAppenderSetting = currentAppenderSetting.copy(appenders = currentAppenderSetting.appenders :+ Log4sAppender.from(appender))
    val updatedState = currentState.copy(appenders = Some(newAppenderSetting))
    LoggerState(parts) = updatedState
  }

  private[log4sjs] final def doLog(e: LoggedEvent): Unit = {
    val state = LoggerState(e.loggerPath)
    if (state.isEnabled(e.level)) {
      for (appender <- state.appenders) {
        appender.append(e)
      }
    }
  }

  private[log4sjs] final def isPathEnabled(path: Seq[String], ll: LogLevel): Boolean = {
    LoggerState(path).isEnabled(ll)
  }

  private[log4sjs] final def isNameEnabled(name: String, ll: LogLevel): Boolean =
    isPathEnabled(CategoryParser(name), ll)
}