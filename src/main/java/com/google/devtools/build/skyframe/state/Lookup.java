// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe.state;

import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.function.Consumer;

/** Captures information about a lookup requested by a state machine. */
abstract class Lookup implements SkyframeLookupResult.QueryDepCallback {
  private final TaskTreeNode parent;
  private final SkyKey key;

  private Lookup(TaskTreeNode parent, SkyKey key) {
    this.parent = parent;
    this.key = key;
  }

  final SkyKey key() {
    return key;
  }

  @Override
  public final void acceptValue(SkyKey unusedKey, SkyValue value) {
    acceptValue(value);
    parent.signalChildDoneAndEnqueueIfReady();
  }

  abstract void acceptValue(SkyValue value);

  @Override
  public final boolean tryHandleException(SkyKey unusedKey, Exception exception) {
    boolean handled = tryHandleException(exception);
    if (handled) {
      parent.signalChildDoneAndEnqueueIfReady();
    }
    return handled;
  }

  abstract boolean tryHandleException(Exception exception);

  static final class ConsumerLookup extends Lookup {
    private final Consumer<SkyValue> sink;

    ConsumerLookup(TaskTreeNode parent, SkyKey key, Consumer<SkyValue> sink) {
      super(parent, key);
      this.sink = sink;
    }

    @Override
    void acceptValue(SkyValue value) {
      sink.accept(value);
    }

    @Override
    boolean tryHandleException(Exception unusedException) {
      return false;
    }
  }

  static final class ValueOrExceptionLookup<E extends Exception> extends Lookup {
    private final Class<E> exceptionClass;
    private final StateMachine.ValueOrExceptionSink<E> sink;

    ValueOrExceptionLookup(
        TaskTreeNode parent,
        SkyKey key,
        Class<E> exceptionClass,
        StateMachine.ValueOrExceptionSink<E> sink) {
      super(parent, key);
      this.exceptionClass = exceptionClass;
      this.sink = sink;
    }

    @Override
    void acceptValue(SkyValue value) {
      sink.accept(value, /* exception= */ null);
    }

    @Override
    boolean tryHandleException(Exception exception) {
      if (exceptionClass.isInstance(exception)) {
        sink.accept(/* value= */ null, exceptionClass.cast(exception));
        return true;
      }
      return false;
    }
  }

  static final class ValueOrException2Lookup<E1 extends Exception, E2 extends Exception>
      extends Lookup {
    private final Class<E1> exceptionClass1;
    private final Class<E2> exceptionClass2;
    private final StateMachine.ValueOrException2Sink<E1, E2> sink;

    ValueOrException2Lookup(
        TaskTreeNode parent,
        SkyKey key,
        Class<E1> exceptionClass1,
        Class<E2> exceptionClass2,
        StateMachine.ValueOrException2Sink<E1, E2> sink) {
      super(parent, key);
      this.exceptionClass1 = exceptionClass1;
      this.exceptionClass2 = exceptionClass2;
      this.sink = sink;
    }

    @Override
    void acceptValue(SkyValue value) {
      sink.accept(value, /* e1= */ null, /* e2= */ null);
    }

    @Override
    boolean tryHandleException(Exception exception) {
      if (exceptionClass1.isInstance(exception)) {
        sink.accept(/* value= */ null, exceptionClass1.cast(exception), /* e2= */ null);
        return true;
      }
      if (exceptionClass2.isInstance(exception)) {
        sink.accept(/* value= */ null, /* e1= */ null, exceptionClass2.cast(exception));
        return true;
      }
      return false;
    }
  }

  static final class ValueOrException3Lookup<
          E1 extends Exception, E2 extends Exception, E3 extends Exception>
      extends Lookup {
    private final Class<E1> exceptionClass1;
    private final Class<E2> exceptionClass2;
    private final Class<E3> exceptionClass3;
    private final StateMachine.ValueOrException3Sink<E1, E2, E3> sink;

    ValueOrException3Lookup(
        TaskTreeNode parent,
        SkyKey key,
        Class<E1> exceptionClass1,
        Class<E2> exceptionClass2,
        Class<E3> exceptionClass3,
        StateMachine.ValueOrException3Sink<E1, E2, E3> sink) {
      super(parent, key);
      this.exceptionClass1 = exceptionClass1;
      this.exceptionClass2 = exceptionClass2;
      this.exceptionClass3 = exceptionClass3;
      this.sink = sink;
    }

    @Override
    void acceptValue(SkyValue value) {
      sink.accept(value, /* e1= */ null, /* e2= */ null, /* e3= */ null);
    }

    @Override
    boolean tryHandleException(Exception exception) {
      if (exceptionClass1.isInstance(exception)) {
        sink.accept(
            /* value= */ null, exceptionClass1.cast(exception), /* e2= */ null, /* e3= */ null);
        return true;
      }
      if (exceptionClass2.isInstance(exception)) {
        sink.accept(
            /* value= */ null, /* e1= */ null, exceptionClass2.cast(exception), /* e3= */ null);
        return true;
      }
      if (exceptionClass3.isInstance(exception)) {
        sink.accept(
            /* value= */ null, /* e1= */ null, /* e2= */ null, exceptionClass3.cast(exception));
        return true;
      }
      return false;
    }
  }
}
