/*
 * Copyright 2014-15 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.core.sysevents

import core.sysevents._

import scala.language.implicitConversions

sealed trait Sysevent {


  def id: String

  def componentId: String

  type EffectBlock[T] = SyseventPublisherContext => T

  private def run[T](ff: => Seq[FieldAndValue], f: EffectBlock[T])(implicit ctx: WithSyseventPublisher): T = {
    val eCtx = ctx.evtPublisher.contextFor(ctx.evtSystem, this, ff)
    val start = if (eCtx.isMute) 0 else System.nanoTime()

    try f(eCtx) catch {
      case e: Throwable =>
        eCtx + ('Exception -> e)
        throw e
    } finally {
      if (!eCtx.isMute) {
        if (ctx.commonFields.nonEmpty) eCtx ++ ctx.commonFields
        val diff = System.nanoTime() - start
        eCtx + ('ms -> ((diff / 1000).toDouble / 1000))
      }
      ctx.evtPublisher.publish(eCtx)
    }
  }
  private def run(ff: => Seq[FieldAndValue])(implicit ctx: WithSyseventPublisher): Unit = {
    val eCtx = ctx.evtPublisher.contextFor(ctx.evtSystem, this, ff)
    if (!eCtx.isMute) {
      if (ctx.commonFields.nonEmpty) eCtx ++ ctx.commonFields
    }
    ctx.evtPublisher.publish(eCtx)
  }

  def apply[T](f: EffectBlock[T])(implicit ctx: WithSyseventPublisher): T = run(Seq.empty, f)

  def apply[T](f1: => FieldAndValue)(implicit ctx: WithSyseventPublisher): Unit = run(Seq(f1))

  def apply[T]()(implicit ctx: WithSyseventPublisher): Unit = run(Seq.empty)

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher): Unit = run(Seq(f1, f2))

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher): Unit = run(Seq(f1, f2, f3))

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue,
               f4: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher): Unit = run(Seq(f1, f2, f3, f4))

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue,
               f4: => FieldAndValue,
               f5: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher): Unit = run(Seq(f1, f2, f3, f4, f5))

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue,
               f4: => FieldAndValue,
               f5: => FieldAndValue,
               f6: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher): Unit = run(Seq(f1, f2, f3, f4, f5, f6))

  def apply[T](f1: => FieldAndValue,
               f2: => FieldAndValue,
               f3: => FieldAndValue,
               f4: => FieldAndValue,
               f5: => FieldAndValue,
               f6: => FieldAndValue,
               f7: => FieldAndValue)
              (implicit ctx: WithSyseventPublisher): Unit = run(Seq(f1, f2, f3, f4, f5, f6, f7))

}


trait SyseventImplicits {
  implicit def stringToSyseventOps(s: String)(implicit component: SyseventComponent): SyseventOps = new SyseventOps(s, component)

  implicit def symbolToSyseventOps(s: Symbol)(implicit component: SyseventComponent): SyseventOps = new SyseventOps(s.name, component)
}

object SyseventOps extends SyseventImplicits


class SyseventOps(id: String, component: SyseventComponent) {
  def trace: Sysevent = TraceSysevent(id, component.componentId)

  def info: Sysevent = InfoSysevent(id, component.componentId)

  def warn: Sysevent = WarnSysevent(id, component.componentId)

  def error: Sysevent = ErrorSysevent(id, component.componentId)

}

case class TraceSysevent(id: String, componentId: String) extends Sysevent

case class InfoSysevent(id: String, componentId: String) extends Sysevent

case class WarnSysevent(id: String, componentId: String) extends Sysevent

case class ErrorSysevent(id: String, componentId: String) extends Sysevent



