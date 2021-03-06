package dotty.tools.dotc
package core

import Types._, Symbols._, Contexts._, util.Stats._, Hashable._, Names._, Designators._
import config.Config
import util.HashSet

/** Defines operation `unique` for hash-consing types.
 *  Also defines specialized hash sets for hash consing uniques of a specific type.
 *  All sets offer a `enterIfNew` method which checks whether a type
 *  with the given parts exists already and creates a new one if not.
 */
object Uniques {

  private def recordCaching(tp: Type): Unit = recordCaching(tp.hash, tp.getClass)
  private def recordCaching(h: Int, clazz: Class[_]): Unit =
    if (h == NotCached) {
      record("uncached-types")
      record(s"uncached: $clazz")
    } else {
      record("cached-types")
      record(s"cached: $clazz")
    }

  def unique[T <: Type](tp: T)(implicit ctx: Context): T = {
    if (monitored) recordCaching(tp)
    if (tp.hash == NotCached) tp
    else if (monitored) {
      val size = ctx.uniques.size
      val result = ctx.uniques.findEntryOrUpdate(tp).asInstanceOf[T]
      if (ctx.uniques.size > size) record(s"fresh unique ${tp.getClass}")
      result
    } else ctx.uniques.findEntryOrUpdate(tp).asInstanceOf[T]
  } /* !!! DEBUG
  ensuring (
    result => tp.toString == result.toString || {
      println(s"cache mismatch; tp = $tp, cached = $result")
      false
    }
  )
 */

  final class NamedTypeUniques extends HashSet[NamedType](Config.initialUniquesCapacity) with Hashable {
    override def hash(x: NamedType): Int = x.hash

    private def findPrevious(h: Int, prefix: Type, designator: Designator): NamedType = {
      var e = findEntryByHash(h)
      while (e != null) {
        if ((e.prefix eq prefix) && (e.designator eq designator)) return e
        e = nextEntryByHash(h)
      }
      e
    }

    def enterIfNew(prefix: Type, designator: Designator, isTerm: Boolean)(implicit ctx: Context): NamedType = {
      val h = doHash(designator, prefix)
      if (monitored) recordCaching(h, classOf[NamedType])
      def newType = {
        if (isTerm) new CachedTermRef(prefix, designator.asInstanceOf[TermDesignator], h)
        else new CachedTypeRef(prefix, designator.asInstanceOf[TypeDesignator], h)
      }.init()
      if (h == NotCached) newType
      else {
        val r = findPrevious(h, prefix, designator)
        if (r ne null) r else addEntryAfterScan(newType)
      }
    }
  }

  final class AppliedUniques extends HashSet[AppliedType](Config.initialUniquesCapacity) with Hashable {
    override def hash(x: AppliedType): Int = x.hash

    private def findPrevious(h: Int, tycon: Type, args: List[Type]): AppliedType = {
      var e = findEntryByHash(h)
      while (e != null) {
        def sameArgs(args1: List[Type], args2: List[Type]): Boolean = {
          val empty1 = args1.isEmpty
          val empty2 = args2.isEmpty
          if (empty1) empty2
          else (!empty2 && (args1.head eq args2.head) && sameArgs(args1.tail, args2.tail))
        }
        if ((e.tycon eq tycon) && sameArgs(e.args, args)) return e
        e = nextEntryByHash(h)
      }
      e
    }

    def enterIfNew(tycon: Type, args: List[Type]): AppliedType = {
      val h = doHash(tycon, args)
      def newType = new CachedAppliedType(tycon, args, h)
      if (monitored) recordCaching(h, classOf[CachedAppliedType])
      if (h == NotCached) newType
      else {
        val r = findPrevious(h, tycon, args)
        if (r ne null) r else addEntryAfterScan(newType)
      }
    }
  }
}
