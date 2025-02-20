// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform.workspace.storage

import com.intellij.platform.workspace.storage.impl.ImmutableEntityStorageImpl
import com.intellij.platform.workspace.storage.query.StorageQuery
import com.intellij.platform.workspace.storage.url.VirtualFileUrlIndex
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls

/**
 * A base interface for immutable [ImmutableEntityStorage] and [MutableEntityStorage].
 */
public interface EntityStorage {
  /**
   * Returns a sequence containing all the entities of the given type from this storage. 
   * There are no guaranties about the order of the elements in the returned sequence.
   */
  public fun <E : WorkspaceEntity> entities(entityClass: Class<E>): Sequence<E>

  /**
   * Returns a sequence containing all entities of type [entityClass] which contains a [SymbolicEntityId] property equal to the given [id].
   * There are no guaranties about the order of the elements in the returned sequence.
   */
  public fun <E : WorkspaceEntityWithSymbolicId, R : WorkspaceEntity> referrers(id: SymbolicEntityId<E>, entityClass: Class<R>): Sequence<R>

  /**
   * Returns an entity which [symbolicId][WorkspaceEntityWithSymbolicId.symbolicId] property is equal to the given [id] or `null` if there 
   * is no such entity.
   */
  public fun <E : WorkspaceEntityWithSymbolicId> resolve(id: SymbolicEntityId<E>): E?

  /**
   * Returns `true` if there is an entity which [symbolicId][WorkspaceEntityWithSymbolicId.symbolicId] property is equal to the given [id].
   * `contains(id)` is equivalent to `resolve(id) != null`, but works a bit faster.
   */
  public operator fun <E : WorkspaceEntityWithSymbolicId> contains(id: SymbolicEntityId<E>): Boolean

  /**
   * Returns a mapping with the given [identifier] which associates entities from this storage with values of type [T]. 
   */
  public fun <T> getExternalMapping(identifier: @NonNls String): ExternalEntityMapping<T>

  /**
   * Returns an index which allows to quickly find entities which refer to a particular [VirtualFileUrl][com.intellij.platform.workspace.storage.url.VirtualFileUrl]
   * in their properties.
   */
  public fun getVirtualFileUrlIndex(): VirtualFileUrlIndex

  /**
   * Returns a map containing all entities from this storage which [entitySource][WorkspaceEntity.entitySource] property satisfies the
   * given [sourceFilter]. 
   */
  public fun entitiesBySource(sourceFilter: (EntitySource) -> Boolean): Map<EntitySource, Map<Class<out WorkspaceEntity>, List<WorkspaceEntity>>>
}

/**
 * Kotlin shortcut for `EntityStorage.entities(E::class.java)`.
 */
public inline fun <reified E: WorkspaceEntity> EntityStorage.entities(): Sequence<E> {
  return this.entities(E::class.java)
}

/**
 * An immutable snapshot of the storage state. 
 * It isn't affected by the further modifications of the storage.
 * 
 * Use [com.intellij.platform.backend.workspace.WorkspaceModel.currentSnapshot] to get an instance representing entities inside the IDE process. 
 */
public interface ImmutableEntityStorage : EntityStorage {
  /**
   * This function is under development, please don't use it.
   */
  @ApiStatus.Experimental
  public fun <T> cached(query: StorageQuery<T>): T

  public companion object {
    public fun empty(): ImmutableEntityStorage = ImmutableEntityStorageImpl.EMPTY
  }
}

/**
 * Creates a mutable copy of `this` storage.
 */
public fun ImmutableEntityStorage.toBuilder(): MutableEntityStorage {
  return MutableEntityStorage.from(this)
}

/**
 * Convert entity storage to the snapshot. If the storage is a snapshot, return itself.
 *
 * This function is obsolete, use [MutableEntityStorage.toSnapshot].
 */
@ApiStatus.Obsolete
public fun EntityStorage.toSnapshot(): ImmutableEntityStorage {
  return when (this) {
    is ImmutableEntityStorage -> this
    is MutableEntityStorage -> this.toSnapshot()
    else -> error("Unexpected storage: $this")
  }
}
