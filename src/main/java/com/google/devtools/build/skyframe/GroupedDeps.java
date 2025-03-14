// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadHostile;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.checkerframework.framework.qual.DefaultQualifierInHierarchy;
import org.checkerframework.framework.qual.LiteralKind;
import org.checkerframework.framework.qual.QualifierForLiterals;
import org.checkerframework.framework.qual.SubtypeOf;

/**
 * Encapsulates Skyframe dependencies, preserving the groups in which they were requested.
 *
 * <p>This class itself does no duplicate checking, although it is expected that a {@code
 * GroupedDeps} instance contains no duplicates - Skyframe is responsible for only adding keys which
 * are not already present.
 *
 * <p>Groups are implemented as lists to minimize memory use. However, {@link #equals} is defined to
 * treat groups as unordered.
 */
public class GroupedDeps implements Iterable<List<SkyKey>> {

  /**
   * Indicates that the annotated element is a compressed {@link GroupedDeps}, so that it can be
   * safely passed to {@link #decompress} and friends.
   */
  @SubtypeOf(DefaultObject.class)
  @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
  @QualifierForLiterals(LiteralKind.NULL)
  public @interface Compressed {}

  /** Default annotation for type-safety checks of {@link Compressed}. */
  @DefaultQualifierInHierarchy
  @SubtypeOf({})
  @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
  private @interface DefaultObject {}

  // Total number of deps. At least elements.size(), but might be larger if there are any nested
  // lists.
  private int size = 0;
  // Dep groups. Each element is either a SkyKey (a singleton group) or ImmutableList<SkyKey>.
  private final ArrayList<Object> elements;

  private final CollectionView collectionView = new CollectionView();

  public GroupedDeps() {
    // We optimize for small lists.
    this.elements = new ArrayList<>(1);
  }

  private GroupedDeps(int size, Object[] elements) {
    this.size = size;
    this.elements = Lists.newArrayList(elements);
  }

  /**
   * Increases the capacity of the backing list to accommodate the given number of additional
   * groups.
   */
  void ensureCapacityForAdditionalGroups(int additionalGroups) {
    elements.ensureCapacity(elements.size() + additionalGroups);
  }

  /**
   * Adds a new group with a single element.
   *
   * <p>The caller must ensure that the given element is not already present.
   */
  public void appendSingleton(SkyKey key) {
    elements.add(key);
    size++;
  }

  /**
   * Adds a new group.
   *
   * <p>The caller must ensure that the new group is duplicate-free and does not contain any
   * elements which are already present.
   */
  public void appendGroup(ImmutableList<SkyKey> group) {
    addGroup(group, elements);
    size += group.size();
  }

  /**
   * Removes the elements in {@code toRemove} from this {@code GroupedDeps}. Takes time proportional
   * to the number of deps, so should not be called often.
   */
  public void remove(Set<SkyKey> toRemove) {
    if (!toRemove.isEmpty()) {
      size = removeAndGetNewSize(elements, toRemove);
    }
  }

  /**
   * Removes everything in {@code toRemove} from the list of lists, {@code elements}. Returns the
   * new number of elements.
   */
  private static int removeAndGetNewSize(List<Object> elements, Set<?> toRemove) {
    int removedCount = 0;
    int newSize = 0;
    // elements.size is an upper bound of the needed size. Since normally removal happens just
    // before the list is finished and compressed, optimizing this size isn't a concern.
    List<Object> newElements = new ArrayList<>(elements.size());
    for (Object obj : elements) {
      if (obj instanceof SkyKey) {
        if (toRemove.contains(obj)) {
          removedCount++;
        } else {
          newElements.add(obj);
          newSize++;
        }
      } else {
        ImmutableList.Builder<Object> newGroup = new ImmutableList.Builder<>();
        for (Object elt : castAsList(obj)) {
          if (toRemove.contains(elt)) {
            removedCount++;
          } else {
            newGroup.add(elt);
            newSize++;
          }
        }
        addGroup(newGroup.build(), newElements);
      }
    }
    // removedCount can be larger if elements had duplicates and the duplicate was also in toRemove.
    checkState(
        removedCount >= toRemove.size(),
        "Requested removal of absent element(s) (toRemove=%s, elements=%s)",
        toRemove,
        elements);
    elements.clear();
    elements.addAll(newElements);
    return newSize;
  }

  /** Returns the group at position {@code i}. {@code i} must be less than {@link #numGroups}. */
  public ImmutableList<SkyKey> getDepGroup(int i) {
    return toList(elements.get(i));
  }

  /** Returns the number of dependency groups. */
  public int numGroups() {
    return elements.size();
  }

  /**
   * Returns the number of individual dependencies, as opposed to the number of groups -- equivalent
   * to adding up the sizes of each dependency group.
   */
  public int numElements() {
    return size;
  }

  public static int numElements(@Compressed Object compressed) {
    switch (compressionCase(compressed)) {
      case EMPTY:
        return 0;
      case SINGLETON:
        return 1;
      case MULTIPLE:
        int size = 0;
        for (Object item : (Object[]) compressed) {
          size += sizeOf(item);
        }
        return size;
    }
    throw new AssertionError(compressed);
  }

  private enum CompressionCase {
    EMPTY,
    SINGLETON,
    MULTIPLE
  }

  private static CompressionCase compressionCase(@Compressed Object compressed) {
    if (compressed == EMPTY_COMPRESSED) {
      return CompressionCase.EMPTY;
    }
    if (compressed instanceof SkyKey) {
      return CompressionCase.SINGLETON;
    }
    checkArgument(compressed.getClass().isArray(), compressed);
    return CompressionCase.MULTIPLE;
  }

  /**
   * Expands a compressed {@code GroupedDeps} into an {@link Iterable}. Equivalent to {@link
   * #getAllElementsAsIterable} but potentially more efficient.
   */
  public static Iterable<SkyKey> compressedToIterable(@Compressed Object compressed) {
    switch (compressionCase(compressed)) {
      case EMPTY:
        return ImmutableList.of();
      case SINGLETON:
        return ImmutableList.of((SkyKey) compressed);
      case MULTIPLE:
        return decompress(compressed).getAllElementsAsIterable();
    }
    throw new AssertionError(compressed);
  }

  /**
   * Casts an {@code Object} which is known to be {@link Compressed}.
   *
   * <p>This method should only be used when it is not possible to enforce the type via annotations.
   */
  public static @Compressed Object castAsCompressed(Object obj) {
    checkArgument(obj == EMPTY_COMPRESSED || obj instanceof SkyKey || obj.getClass().isArray());
    return (@Compressed Object) obj;
  }

  /** Returns true if this list contains no elements. */
  public boolean isEmpty() {
    return elements.isEmpty();
  }

  /** Determines whether the given compressed {@code GroupedDeps} is empty. */
  public static boolean isEmpty(@Compressed Object compressed) {
    return compressed == EMPTY_COMPRESSED;
  }

  /**
   * Returns true if this list contains {@code needle}. May take time proportional to list size.
   * Call {@link #toSet} instead and use the result if doing multiple contains checks and this is
   * not a {@link WithHashSet}.
   */
  public boolean contains(SkyKey needle) {
    return contains(elements, needle);
  }

  private static boolean contains(List<Object> elements, Object needle) {
    for (Object obj : elements) {
      if (obj instanceof SkyKey) {
        if (obj.equals(needle)) {
          return true;
        }
      } else if (castAsList(obj).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  @SerializationConstant @AutoCodec.VisibleForSerialization
  static final @Compressed Object EMPTY_COMPRESSED = new Object();

  /**
   * Returns a memory-efficient representation of dependency groups.
   *
   * <p>The compressed representation does not support mutation or random access to dep groups. If
   * this functionality is needed, use {@link #decompress}.
   */
  public @Compressed Object compress() {
    switch (numElements()) {
      case 0:
        return EMPTY_COMPRESSED;
      case 1:
        return elements.get(0);
      default:
        return elements.toArray();
    }
  }

  public ImmutableSet<SkyKey> toSet() {
    ImmutableSet.Builder<SkyKey> builder = ImmutableSet.builderWithExpectedSize(size);
    for (Object obj : elements) {
      if (obj instanceof SkyKey) {
        builder.add((SkyKey) obj);
      } else {
        builder.addAll(castAsList(obj));
      }
    }
    return builder.build();
  }

  private static int sizeOf(Object obj) {
    return obj instanceof SkyKey ? 1 : castAsList(obj).size();
  }

  private static ImmutableList<SkyKey> toList(Object obj) {
    return obj instanceof SkyKey ? ImmutableList.of((SkyKey) obj) : castAsList(obj);
  }

  @SuppressWarnings("unchecked") // Cast of Object to ImmutableList<SkyKey>.
  private static ImmutableList<SkyKey> castAsList(Object obj) {
    return (ImmutableList<SkyKey>) obj;
  }

  /** Reconstitutes a compressed representation returned by {@link #compress}. */
  public static GroupedDeps decompress(@Compressed Object compressed) {
    switch (compressionCase(compressed)) {
      case EMPTY:
        return new GroupedDeps();
      case SINGLETON:
        return new GroupedDeps(1, new Object[] {compressed});
      case MULTIPLE:
        return new GroupedDeps(numElements(compressed), (Object[]) compressed);
    }
    throw new AssertionError(compressed);
  }

  @Override
  public int hashCode() {
    // Hashing requires getting an order-independent hash for each element of this.elements. That
    // is too expensive for a hash code.
    throw new UnsupportedOperationException("Should not need to get hash for " + this);
  }

  /**
   * Checks that two lists, neither of which may contain duplicates, have the same elements,
   * regardless of order.
   */
  private static boolean checkUnorderedEqualityWithoutDuplicates(List<?> first, List<?> second) {
    if (first.size() != second.size()) {
      return false;
    }
    // The order-sensitive comparison usually returns true. When it does, the CompactHashSet
    // doesn't need to be constructed.
    return first.equals(second) || CompactHashSet.create(first).containsAll(second);
  }

  /**
   * A grouping-unaware view which does not support modifications.
   *
   * <p>This is implemented as a {@code Collection} so that calling {@link Iterables#size} on the
   * return value of {@link #getAllElementsAsIterable} will take constant time.
   */
  private final class CollectionView extends AbstractCollection<SkyKey> {

    @Override
    public Iterator<SkyKey> iterator() {
      return new UngroupedIterator(elements);
    }

    @Override
    public int size() {
      return size;
    }
  }

  /** An iterator that loops through every element in each group. */
  private static final class UngroupedIterator implements Iterator<SkyKey> {
    private final List<Object> elements;
    private int outerIndex = 0;
    @Nullable private List<SkyKey> currentGroup;
    private int innerIndex = 0;

    private UngroupedIterator(List<Object> elements) {
      this.elements = elements;
    }

    @Override
    public boolean hasNext() {
      return outerIndex < elements.size();
    }

    @Override
    public SkyKey next() {
      if (currentGroup != null) {
        return nextFromCurrentGroup();
      }
      Object next = elements.get(outerIndex);
      if (next instanceof SkyKey) {
        outerIndex++;
        return (SkyKey) next;
      }
      currentGroup = castAsList(next);
      innerIndex = 0;
      return nextFromCurrentGroup();
    }

    private SkyKey nextFromCurrentGroup() {
      SkyKey next = currentGroup.get(innerIndex++);
      if (innerIndex == currentGroup.size()) {
        outerIndex++;
        currentGroup = null;
      }
      return next;
    }
  }

  @ThreadHostile
  public Collection<SkyKey> getAllElementsAsIterable() {
    return collectionView;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GroupedDeps)) {
      return false;
    }
    GroupedDeps that = (GroupedDeps) other;
    // We must check the deps, ignoring the ordering of deps in the same group.
    if (this.size != that.size || this.elements.size() != that.elements.size()) {
      return false;
    }
    for (int i = 0; i < this.elements.size(); i++) {
      Object thisElt = this.elements.get(i);
      Object thatElt = that.elements.get(i);
      if (thisElt == thatElt) {
        continue;
      }
      if (thisElt instanceof SkyKey) {
        if (!thisElt.equals(thatElt)) {
          return false;
        }
      } else if (!(thatElt instanceof List)) {
        return false;
      } else if (!checkUnorderedEqualityWithoutDuplicates(
          castAsList(thisElt), castAsList(thatElt))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("elements", elements).add("size", size).toString();
  }

  /**
   * Iterator that returns the next group in elements for each call to {@link #next}. A custom
   * iterator is needed here because, to optimize memory, we store single-element lists as elements
   * internally, and so they must be wrapped before they're returned.
   */
  private class GroupedIterator implements Iterator<List<SkyKey>> {
    private final Iterator<Object> iter = elements.iterator();

    @Override
    public boolean hasNext() {
      return iter.hasNext();
    }

    @Override
    public ImmutableList<SkyKey> next() {
      return toList(iter.next());
    }
  }

  @Override
  public Iterator<List<SkyKey>> iterator() {
    return new GroupedIterator();
  }

  /**
   * If {@code group} is empty, this function does nothing.
   *
   * <p>If it contains a single element, then that element is added to {@code elements}.
   *
   * <p>If it contains more than one element, then it is added as the next element of {@code
   * elements} (this means {@code elements} may contain both raw objects and {@link
   * ImmutableList}s).
   *
   * <p>Use with caution as there are no checks in place for the integrity of the resulting object
   * (no de-duping or verifying there are no nested lists).
   */
  private static void addGroup(ImmutableList<?> group, List<Object> elements) {
    switch (group.size()) {
      case 0:
        return;
      case 1:
        elements.add(group.get(0));
        return;
      default:
        elements.add(group);
    }
  }

  /**
   * A {@link GroupedDeps} which keeps a {@link HashSet} of its elements up to date, resulting in a
   * higher memory cost and faster {@link #contains} operations.
   */
  public static class WithHashSet extends GroupedDeps {
    private final HashSet<SkyKey> set = new HashSet<>();

    @Override
    public void appendSingleton(SkyKey key) {
      super.appendSingleton(key);
      set.add(key);
    }

    @Override
    public void appendGroup(ImmutableList<SkyKey> group) {
      super.appendGroup(group);
      set.addAll(group);
    }

    @Override
    public void remove(Set<SkyKey> toRemove) {
      super.remove(toRemove);
      set.removeAll(toRemove);
    }

    @Override
    public boolean contains(SkyKey needle) {
      return set.contains(needle);
    }

    @Override
    public ImmutableSet<SkyKey> toSet() {
      return ImmutableSet.copyOf(set);
    }
  }
}
