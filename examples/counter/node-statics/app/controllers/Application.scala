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

package controllers

import play.api.mvc._

object Application extends Controller {


  def index = Action {
    Ok(views.html.index())
  }

//  def socket = {
//    println(s"!>>>  ok ...")
//    WebSocket.acceptWithActor[String, String] {
//      req => actor =>
//        println(s"!>>>  About to start actor ...")
//        val r = WebsocketActor.props(actor)
//        println(s"!>>>  Started actor .. = " + r)
//        r
//    }
//  }


}