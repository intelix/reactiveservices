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

define(['react', 'core/mixin', 'core/authmixin', 'classnames', './SecuredContent'], function (React, RSMixin, AuthMixin, Classnames, SecuredContent) {

    return React.createClass({

        mixins: [RSMixin, AuthMixin],

        componentName: function () {
            return "app/content/commons/Login";
        },

        handleSubmit: function () {
            var user = this.refs.formUser.value;
            var passw = this.refs.formPassword.value;
            this.performCredentialsAuthentication(user, passw);
        },

        renderUnsecured: function () {
            var self = this;

            var buttonClasses = Classnames({
                'disabled': (!self.state.connected || self.isAuthenticationPending()),
                'btn btn-lg btn-primary btn-block': true
            });
            var fieldClasses = Classnames({
                'disabled': (self.isAuthenticationPending()),
                'form-control': true
            });

            var buttonText = "Log in";
            if (!self.state.connected) {
                buttonText = "Connecting ...";
            }
            if (self.isAuthenticationPending()) {
                buttonText = "Authenticating ...";
            }

            return (
                <div className="container">
                    <form className="form-signin">
                        <div>
                            <input type="test" id="input-user" className={fieldClasses} placeholder="Username"
                                          required="true" autofocus="true" ref="formUser"/> (user1 or user2)
                        </div>
                        <div>
                            <input type="password" id="input-password" className={fieldClasses}
                                             placeholder="Password"
                                             required="true" ref="formPassword"/> (password123)
                        </div>
                        <div>
                            <button className={buttonClasses} type="button" onClick={this.handleSubmit}>
                                {buttonText}
                            </button>
                        </div>
                    </form>
                </div>
            );

        },

        renderSecured: function () {
            return <SecuredContent {...this.props} />;
        }

    });

});