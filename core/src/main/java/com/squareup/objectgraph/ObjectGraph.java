/*
 * Copyright (C) 2012 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.objectgraph;

import com.squareup.objectgraph.internal.Binding;
import com.squareup.objectgraph.internal.Keys;
import com.squareup.objectgraph.internal.Linker;
import com.squareup.objectgraph.internal.ModuleAdapter;
import com.squareup.objectgraph.internal.ProblemDetector;
import com.squareup.objectgraph.internal.RuntimeLinker;
import com.squareup.objectgraph.internal.StaticInjection;
import com.squareup.objectgraph.internal.UniqueMap;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A graph of objects linked by their dependencies.
 *
 * <p>The following injection features are supported:
 * <ul>
 *   <li>Field injection. A class may have any number of field injections, and
 *       fields may be of any visibility. Static fields will be injected each
 *       time an instance is injected.
 *   <li>Constructor injection. A class may have a single
 *       {@code @Inject}-annotated constructor. Classes that have fields
 *       injected may omit the {@link @Inject} annotation if they have a public
 *       no-arguments constructor.
 *   <li>Injection of {@code @Provides} method parameters.
 *   <li>{@code @Provides} methods annotated {@code @Singleton}.
 *   <li>Constructor-injected classes annotated {@code @Singleton}.
 *   <li>Injection of {@link javax.inject.Provider}s.
 *   <li>Injection of {@link MembersInjector}s.
 *   <li>Qualifier annotations on injected parameters and fields.
 *   <li>JSR 330 annotations.
 * </ul>
 *
 * <p>The following injection features are not currently supported:
 * <ul>
 *   <li>Method injection.</li>
 *   <li>Circular dependencies.</li>
 * </ul>
 */
public final class ObjectGraph {
  private final Linker linker;
  private final Map<Class<?>, StaticInjection> staticInjections;
  private final Map<String, Class<?>> entryPoints;

  private ObjectGraph(Linker linker, Map<Class<?>, StaticInjection> staticInjections,
      Map<String, Class<?>> entryPoints) {
    this.linker = linker;
    this.staticInjections = staticInjections;
    this.entryPoints = entryPoints;
  }

  /**
   * Returns a new dependency graph using the {@literal @}{@link
   * Module}-annotated modules.
   *
   * <p>This <strong>does not</strong> inject any members. Most applications
   * should call {@link #injectStatics} to inject static members and/or {@link
   * #inject} to inject instance members when this method has returned.
   */
  public static ObjectGraph get(Object... modules) {
    return get(false, modules);
  }

  public static ObjectGraph getLazy(Object... modules) {
    return get(true, modules);
  }

  private static ObjectGraph get(boolean lazy, Object... modules) {
    Map<String, Class<?>> entryPoints = new LinkedHashMap<String, Class<?>>();
    Map<Class<?>, StaticInjection> staticInjections
        = new LinkedHashMap<Class<?>, StaticInjection>();

    // Extract bindings in the 'base' and 'overrides' set. Within each set no
    // duplicates are permitted.
    Map<String, Binding<?>> baseBindings = new UniqueMap<String, Binding<?>>();
    Map<String, Binding<?>> overrideBindings = new UniqueMap<String, Binding<?>>();
    for (Object module : modules) {
      Class<?> moduleClass = module.getClass();
      ModuleAdapter<Object> adapter = ModuleAdapter.get(module);
      for (String key : adapter.entryPoints) {
        entryPoints.put(key, moduleClass);
      }
      for (Class<?> c : adapter.staticInjections) {
        staticInjections.put(c, lazy ? null : StaticInjection.get(c));
      }
      Map<String, Binding<?>> addTo = adapter.overrides ? overrideBindings : baseBindings;
      adapter.getBindings(module, addTo);
    }

    // Create a linker and install all of the user's bindings.
    Linker linker = new RuntimeLinker();
    linker.installBindings(baseBindings);
    linker.installBindings(overrideBindings);

    ObjectGraph result = new ObjectGraph(linker, staticInjections, entryPoints);

    // Link all bindings (unless this object graph is lazy).
    if (!lazy) {
      result.linkStaticInjections();
      result.linkEntryPoints();
      linker.linkAll();
    }

    return result;
  }

  private void linkStaticInjections() {
    for (Map.Entry<Class<?>, StaticInjection> entry : staticInjections.entrySet()) {
      StaticInjection staticInjection = entry.getValue();
      if (staticInjection == null) {
        staticInjection = StaticInjection.get(entry.getKey());
        entry.setValue(staticInjection);
      }
      staticInjection.attach(linker);
    }
  }

  private void linkEntryPoints() {
    for (Map.Entry<String, Class<?>> entry : entryPoints.entrySet()) {
      linker.requestBinding(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Do full graph problem detection.
   */
  public void detectProblems() {
    linkStaticInjections();
    linkEntryPoints();
    Collection<Binding<?>> allBindings = linker.linkAll();
    new ProblemDetector().detectProblems(allBindings);
  }

  /**
   * Injects the static fields of the classes listed in the object graph's
   * {@code staticInjections} property.
   */
  public void injectStatics() {
    // We call linkStaticInjections() twice on purpose. The first time through
    // we request all of the bindings we need. The linker returns null for
    // bindings it doesn't have. Then we ask the linker to link all of those
    // requested bindings. Finally we call linkStaticInjections() again: this
    // time the linker won't return null because everything has been linked.
    linkStaticInjections();
    linker.linkRequested();
    linkStaticInjections();

    for (Map.Entry<Class<?>, StaticInjection> entry : staticInjections.entrySet()) {
      entry.getValue().inject();
    }
  }

  /**
   * Injects the members of {@code instance}, including injectable members
   * inherited from its supertypes.
   *
   * @throws IllegalArgumentException if the runtime type of {@code instance} is
   *     not the object graph's type or one of its entry point types.
   */
  @SuppressWarnings("unchecked") // the linker matches keys to bindings by their type
  public void inject(Object instance) {
    String key = Keys.getMembersKey(instance.getClass());
    Class<?> moduleClass = entryPoints.get(key);
    if (moduleClass == null) {
      throw new IllegalArgumentException("No entry point for " + instance.getClass().getName()
          + ". You must explicitly add an entry point to one of your modules.");
    }
    Binding<?> binding = linker.requestBinding(key, moduleClass);
    if (binding == null || !binding.linked) {
      linker.linkRequested();
      binding = linker.requestBinding(key, moduleClass);
    }
    ((Binding<Object>) binding).injectMembers(instance);
  }
}