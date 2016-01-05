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


define(['lodash', './logging', 'signals', './jdataview', './socket'], function (_, Log, Signal, JDataView, Socket) {

    var signals = {
        streamUpdateReceived: new Signal(),
        serviceNotAvailableReceived: new Signal(),
        invalidRequestReceived: new Signal(),
        pingReceived: new Signal(),
        frameProcessed: new Signal(),
        signalResult: new Signal()
    };

    var componentId = "core/Codec";

    var TypeNone = 0;
    var TypeNil = 1;
    var TypeByte = 2;
    var TypeShort = 3;
    var TypeInt = 4;
    var TypeLong = 5;
    var TypeFloat = 6;
    var TypeDouble = 7;
    var TypeBoolean = 8;
    var TypeString = 9;

    var TypeServiceNotAvailable = 20;
    var TypeOpenSubscription = 21;
    var TypeCloseSubscription = 22;
    var TypeInvalidRequest = 23;
    var TypeStreamStateUpdate = 24;
    var TypeAlias = 26;
    var TypePing = 27;
    var TypePong = 28;
    var TypeStreamStateTransitionUpdate = 29;
    var TypeSignal = 30;
    var TypeSignalAckOk = 31;
    var TypeSignalAckFailed = 32;
    var TypeSubscriptionClosed = 33;
    var TypeResetSubscription = 34;


    var decoders = [];
    var encoders = [];


    function _addDecoder(type, decoder) {
        decoders[type] = decoder;
    }

    function _getDecoderFor(type) {
        if (decoders.length <= type || _.isUndefined(decoders[type])) {
            throw new Error("No decoder for type " + type);
        }
        return decoders[type];
    }

    function _addEncoder(type, encoder) {
        encoders[type] = encoder;
    }

    function _getEncoderFor(type) {
        if (encoders.length <= type || _.isUndefined(encoders[type])) {
            return false;
        }
        return encoders[type];
    }

    function _writeByte(value) {
        _allocBuffer(1).writeInt8(value);
    }

    function _writeShort(value) {
        _allocBuffer(2).writeInt16(value);
    }

    function _writeInt(value) {
        _allocBuffer(4).writeInt32(value);
    }

    function _getCharCodes(string) {
        var codes = new Uint8Array(string.length);

        for (var i = 0, length = string.length; i < length; i++) {
            codes[i] = string.charCodeAt(i) & 0xff;
        }
        return codes;
    }

    function _writeString(value) {
        var utf8bytes = _getCharCodes(unescape(encodeURIComponent(value)));
        var len = utf8bytes.length;
        _writeInt(len);
        _allocBuffer(len).writeBytes(utf8bytes);
    }

    function _writeServiceKey(value) {
        _writeString(value);
    }

    function _writeTopicKey(value) {
        _writeString(value);
    }

    function _writeSubjectAlias(value) {
        _allocBuffer(4).writeInt32(value);
    }

    function _writeSubject(value) {
        var service = value.service;
        var topic = value.topic;
        _writeServiceKey(service);
        _writeTopicKey(topic);
    }

    function _writeOptionAny(value) {
        if (_.isUndefined(value))
            _writeByte(0);
        else {
            _writeByte(1);
            _writeNext(value);
        }
    }

    function _writeOptionString(value) {
        if (_.isUndefined(value))
            _writeByte(0);
        else {
            _writeByte(1);
            _writeString(value);
        }
    }

    function _writeType(value) {
        _writeByte(value);
    }

    _addEncoder(TypeString, function (value) {
        _writeType(TypeString);
        _writeString(value.value);
        return false;
    });


    _addEncoder(TypeOpenSubscription, function (value) {
        var priorityKey = _.isUndefined(value.priority) ? "" : value.priority;
        var throttling = _.isUndefined(value.throttling) ? 0 : value.throttling;
        _writeType(TypeOpenSubscription);
        _writeSubjectAlias(value.alias);
        _writeString(priorityKey);
        _writeInt(throttling);
        return true;
    });
    _addEncoder(TypeResetSubscription, function (value) {
        _writeType(TypeResetSubscription);
        _writeSubjectAlias(value.alias);
        return true;
    });


    _addEncoder(TypeCloseSubscription, function (value) {
        _writeType(TypeCloseSubscription);
        _writeSubjectAlias(value.alias);
    });

    _addEncoder(TypeSignal, function (value) {
        _writeType(TypeSignal);
        _writeSubjectAlias(value.alias);
        _writeNext(value.payload);
        _writeInt(value.expireInSeconds);
        _writeOptionAny(value.orderingGroup);
        _writeOptionAny(value.correlationId);
        return true;
    });


    _addEncoder(TypeAlias, function (value) {
        _writeType(TypeAlias);
        _writeSubjectAlias(value.alias);
        _writeSubject(value.subject);
        return false;
    });

    _addEncoder(TypePong, function (value) {
        _writeType(TypePong);
        _writeInt(value.id);
        return true;
    });


    // TODO later: UI-side configuration
    var outboundBufferSize = 1024 * 64;
    var flushThresholdFactor = 0.8;
    var _internalBuffer = JDataView(outboundBufferSize);

    function _allocBuffer(bytesRequired) {
        if (outboundBufferSize - _internalBuffer.tell() < bytesRequired + 1) {
            if (Log.isDebug()) Log.logDebug(componentId, "Outbound buffer increased from " + outboundBufferSize + " to " + (outboundBufferSize * 2));
            outboundBufferSize = outboundBufferSize * 2;
            // TODO later: limitation/warnings
            var bytes = _internalBuffer.getBytes(_internalBuffer.tell(), 0);
            _internalBuffer = JDataView(outboundBufferSize);
            _internalBuffer.setBytes(0, bytes);
        }
        return _internalBuffer;
    }

    function _hasData() {
        return _internalBuffer.tell() > 0;
    }

    function _usedBufferSize() {
        return _internalBuffer.tell();
    }

    function _resetBuffer() {
        _internalBuffer.seek(0);
    }

    function _dataFromBuffer() {
        return _internalBuffer.getBytes(_internalBuffer.tell(), 0);
    }

    function _flush() {
        if (flushTimer) clearTimeout(flushTimer);
        flushTimer = false;
        if (_hasData()) {
            var bytes = _dataFromBuffer();
            _resetBuffer();
            Socket.sendFrame(bytes);
        }
    }

    function _shouldFlushNow() {
        return _usedBufferSize() > outboundBufferSize * flushThresholdFactor;
    }

    var flushTimer = false;

    function _scheduleFlush() {
        if (_shouldFlushNow()) _flush();
        else if (!flushTimer) flushTimer = setTimeout(_flush, 50);
    }

    function _writeNext(data) {
        if (_.isUndefined(data.type) || !_.isNumber(data.type)) {
            Log.logWarn(componentId, "Invalid outbound message: " + JSON.stringify(data));
            return false;
        } else {
            var enc = _getEncoderFor(data.type);
            if (!enc) {
                Log.logWarn(componentId, "No encoder for type " + data.type);
                return false;
            } else {
                return enc(data);
            }
        }
    }

    function _handleSerialization(data) {
        if (_writeNext(data)) _flush(); else _scheduleFlush();
    }


    function _frameHasMoreData(frame) {
        return frame.byteLength > frame.tell();
    }

    function _readNextIfAny(frame) {
        if (_frameHasMoreData(frame)) {
            var type = _readByte(frame);
            var dec = _getDecoderFor(type);
            return dec(frame);
        } else return false;
    }

    function _handleDeserialization(frame) {
        var eventsCount = 0;
        var frameWrapper = JDataView(frame);
        try {
            var next = _readNextIfAny(frameWrapper);
            while (next) {
                if (!_.isUndefined(next.handler)) {
                    next.handler();
                    eventsCount++;
                }
                next = _readNextIfAny(frameWrapper);
            }
        } catch (error) {
            Log.logError(componentId, "Error during deserialization: " + error);
        }
        if (eventsCount > 0) signals.frameProcessed.dispatch();
    }

    function _readByte(frame) {
        return frame.getInt8();
    }

    function _readShort(frame) {
        return frame.getInt16();
    }

    function _readInt(frame) {
        return frame.getInt32();
    }

    function _readLong(frame) {
        return frame.getInt64();
    }

    function _readFloat(frame) {
        return frame.getFloat32();
    }

    function _readDouble(frame) {
        return frame.getFloat64();
    }

    function _readBoolean(frame) {
        return _readByte(frame) == 1;
    }

    function _readString(frame) {
        var len = _readInt(frame);
        return frame.getString(len, frame.tell(), "utf-8");
    }

    function _readServiceKey(frame) {
        return _readString(frame);
    }

    function _readTopicKey(frame) {
        return _readString(frame);
    }

    function _readSubjectAlias(frame) {
        return frame.getInt32();
    }


    function _readOptionOfAny(frame) {
        if (_readByte(frame) === 0)
            return false;
        else
            return _readNextIfAny(frame);
    }

    function _readArrayOfAny(frame) {
        var len = _readInt(frame);
        var arr = [];
        for (var i = 0; i < len; i++) arr.push(_readNextIfAny(frame));
        return arr;
    }

    function _readArrayOfString(frame) {
        var len = _readInt(frame);
        var arr = [];
        for (var i = 0; i < len; i++) arr.push(_readString(frame));
        return arr;
    }

    _addDecoder(TypeNone, function () {
        return false;
    });
    _addDecoder(TypeNil, function () {
        return null;
    });
    _addDecoder(TypeByte, _readByte);
    _addDecoder(TypeShort, _readShort);
    _addDecoder(TypeInt, _readInt);
    _addDecoder(TypeLong, _readLong);
    _addDecoder(TypeFloat, _readFloat);
    _addDecoder(TypeDouble, _readDouble);
    _addDecoder(TypeBoolean, _readBoolean);
    _addDecoder(TypeString, _readString);

    _addDecoder(TypeServiceNotAvailable, function (frame) {
        var service = _readServiceKey(frame);
        return {
            handler: function () {
                signals.serviceNotAvailableReceived.dispatch(service);
            }
        };
    });
    _addDecoder(TypeInvalidRequest, function (frame) {
        var alias = _readSubjectAlias(frame);
        return {
            handler: function () {
                signals.invalidRequestReceived.dispatch(alias);
            }
        };
    });
    _addDecoder(TypeSubscriptionClosed, function (frame) {
        var alias = _readSubjectAlias(frame);
        return {};
    });
    _addDecoder(TypePing, function (frame) {
        var id = _readInt(frame);
        return {
            handler: function () {
                signals.pingReceived.dispatch(id);
            }
        };
    });
    _addDecoder(TypeStreamStateUpdate, function (frame) {
        var alias = _readSubjectAlias(frame);
        var data = _readNextIfAny(frame);

        return {
            handler: function () {
                signals.streamUpdateReceived.dispatch(alias, data);
            }
        };
    });
    _addDecoder(TypeStreamStateTransitionUpdate, function (frame) {

        var alias = _readSubjectAlias(frame);
        var data = _readNextIfAny(frame);

        return {
            handler: function () {
                signals.streamUpdateReceived.dispatch(alias, data);
            }
        };
    });
    _addDecoder(TypeSignalAckOk, function (frame) {

        var alias = _readSubjectAlias(frame);
        var correlationId = _readOptionOfAny(frame);
        var payload = _readOptionOfAny(frame);

        return {
            handler: function () {
                signals.signalResult.dispatch(alias, correlationId, true, payload);
            }
        };

    });
    _addDecoder(TypeSignalAckFailed, function (frame) {
        var alias = _readSubjectAlias(frame);
        var correlationId = _readOptionOfAny(frame);
        var payload = _readOptionOfAny(frame);

        return {
            handler: function () {
                signals.signalResult.dispatch(alias, correlationId, false, payload);
            }
        };
    });


    function _outbound(msg) {
        _handleSerialization(msg);
    }

    function _newAlias(alias, service, topic) {
        _outbound({
            type: TypeAlias,
            alias: alias,
            subject: {
                service: service,
                topic: topic
            }
        });
    }

    function _subscribe(alias, priority, throttling) {
        _outbound({
            type: TypeOpenSubscription,
            alias: alias,
            priority: priority,
            throttling: throttling
        });
    }

    function _reset(alias) {
        _outbound({
            type: TypeResetSubscription,
            alias: alias
        });
    }

    function _unsubscribe(alias) {
        _outbound({
            type: TypeCloseSubscription,
            alias: alias
        });
    }

    function _pong(id) {
        _outbound({
            type: TypePong,
            id: id
        });
    }

    function _signal(alias, data, orderingGroup, expireInSeconds, correlationId) {
        var dataAsString = _.isObject(data) ? JSON.stringify(data) : data;

        _outbound({
            type: TypeSignal,
            alias: alias,
            payload: {
                type: TypeString,
                value: dataAsString
            },
            orderingGroup: !_.isUndefined(orderingGroup) ? {
                type: TypeString,
                value: orderingGroup
            } : orderingGroup,
            expireInSeconds: _.isUndefined(expireInSeconds) ? 60 : expireInSeconds,
            correlationId: !_.isUndefined(correlationId) ? {
                type: TypeString,
                value: correlationId
            } : correlationId
        });
    }

    Socket.signals.receivedFrame.add(_handleDeserialization);


    return {

        addDecoder: _addDecoder,
        addEncoder: _addEncoder,

        writeByte: _writeByte,
        writeShort: _writeShort,
        writeInt: _writeInt,
        writeString: _writeString,
        writeServiceKey: _writeServiceKey,
        writeTopicKey: _writeTopicKey,
        writeSubjectAlias: _writeSubjectAlias,
        writeType: _writeType,

        readNextIfAny: _readNextIfAny,
        readByte: _readByte,
        readShort: _readShort,
        readInt: _readInt,
        readFloat: _readFloat,
        readDouble: _readDouble,
        readBoolean: _readBoolean,
        readString: _readString,
        readServiceKey: _readServiceKey,
        readTopicKey: _readTopicKey,
        readOptionOfAny: _readOptionOfAny,
        readArrayOfAny: _readArrayOfAny,
        readArrayOfString: _readArrayOfString,

        write: _outbound,

        signals: signals,
        newAlias: _newAlias,
        subscribe: _subscribe,
        reset: _reset,
        unsubscribe: _unsubscribe,
        signal: _signal,
        pong: _pong

    };

});
