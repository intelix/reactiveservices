/*
 * Copyright 2014-16 Intelix Pty Ltd
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

package au.com.intelix.essentials.time

import java.util.Date

import org.ocpsoft.prettytime.PrettyTime

trait NowProvider {

  def now = java.lang.System.currentTimeMillis()
  def prettyTime = new PrettyTime()
  def prettyTimeFormat(l:Long) = prettyTime.format(new Date(l))

}

object NowProvider extends NowProvider
