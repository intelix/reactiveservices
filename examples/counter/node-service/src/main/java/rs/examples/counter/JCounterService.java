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
package rs.examples.counter;

import rs.core.Subject;
import rs.core.javaapi.JServiceActor;
import scala.Option;

public class JCounterService extends JServiceActor {

    public JCounterService(String id) {
        super(id);
    }

    private int counter = 0;

    @Override
    public void serviceInitialization() {

        onTopicSubscription("counter", "counter");

        onMessage(String.class, new MessageCallback<String>() {
            @Override
            public void handle(String v) {
                publishCounter();
                scheduleOnceToSelf(2000, "tick");
            }
        });

        onSignalForTopic("reset", new SignalCallback() {
            @Override
            public Option<SignalResponse> handle(Subject subj, Object payload) {
                counter = 0;
                publishCounter();
                return success();
            }
        });

        scheduleOnceToSelf(2000, "tick");

    }

    private void publishCounter() {
        streamString("counter", String.valueOf(counter++));
    }

    @Override
    public String componentId() {
        return "CounterService";
    }
}



