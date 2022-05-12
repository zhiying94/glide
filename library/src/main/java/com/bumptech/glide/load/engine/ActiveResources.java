package com.bumptech.glide.load.engine;

import android.os.Process;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.EngineResource.ResourceListener;
import com.bumptech.glide.util.Executors;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Synthetic;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

final class ActiveResources {
  private final boolean isActiveResourceRetentionAllowed;
  private final Executor monitorClearedResourcesExecutor;
  @VisibleForTesting final Map<Key, ResourceWeakReference> activeEngineResources = new HashMap<>();
  private final ReferenceQueue<EngineResource<?>> resourceReferenceQueue = new ReferenceQueue<>();
  /**
   * 一般engine监听次方法
   */
  private ResourceListener listener;

  private volatile boolean isShutdown;
  @Nullable private volatile DequeuedResourceCallback cb;

  ActiveResources(boolean isActiveResourceRetentionAllowed) {
    this(
        isActiveResourceRetentionAllowed,
        java.util.concurrent.Executors.newSingleThreadExecutor(
            new ThreadFactory() {
              @Override
              public Thread newThread(@NonNull final Runnable r) {
                return new Thread(
                    new Runnable() {
                      @Override
                      public void run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        r.run();
                      }
                    },
                    "glide-active-resources");
              }
            }));
  }

  @VisibleForTesting
  ActiveResources(
      boolean isActiveResourceRetentionAllowed, Executor monitorClearedResourcesExecutor) {
    this.isActiveResourceRetentionAllowed = isActiveResourceRetentionAllowed;
    this.monitorClearedResourcesExecutor = monitorClearedResourcesExecutor;

    monitorClearedResourcesExecutor.execute(
        new Runnable() {
          @Override
          public void run() {
            cleanReferenceQueue();
          }
        });
  }

  void setListener(ResourceListener listener) {
    synchronized (listener) {
      synchronized (this) {
        this.listener = listener;
      }
    }
  }

  /**
   * activate方法，相当于put
   */
  synchronized void activate(Key key, EngineResource<?> resource) {
    ResourceWeakReference toPut =
        new ResourceWeakReference(
            key, resource, resourceReferenceQueue, isActiveResourceRetentionAllowed);

    ResourceWeakReference removed = activeEngineResources.put(key, toPut);
    if (removed != null) {
      //移除的弱引用对象需要清除强引用
      removed.reset();
    }
  }

  synchronized void deactivate(Key key) {
    ResourceWeakReference removed = activeEngineResources.remove(key);
    if (removed != null) {
      removed.reset();
    }
  }

  /**清除当前被GC的ref对象*/
  @SuppressWarnings({"WeakerAccess", "SynchronizeOnNonFinalField"})
  @Synthetic
  void cleanupActiveReference(@NonNull ResourceWeakReference ref) {
    synchronized (this) {
      activeEngineResources.remove(ref.key);

      if (!ref.isCacheable || ref.resource == null) {
        return;
      }
    }
    //创建新的对象EngineResource，复用ref.resource，
    EngineResource<?> newResource =
        new EngineResource<>(
            ref.resource, /*isMemoryCacheable=*/ true, /*isRecyclable=*/ false, ref.key, listener);
    listener.onResourceReleased(ref.key, newResource);
  }

  @Nullable
  synchronized EngineResource<?> get(Key key) {
    //通过 HashMap + WeakReference 的存储结构
    //通过 HashMap 的 get 函数拿到活动缓存
    ResourceWeakReference activeRef = activeEngineResources.get(key);
    if (activeRef == null) {
      return null;
    }

    EngineResource<?> active = activeRef.get();
    if (active == null) {
      cleanupActiveReference(activeRef);
    }
    return active;
  }
  /**清除回收对象*/
  @SuppressWarnings("WeakerAccess")
  @Synthetic
  void cleanReferenceQueue() {
    while (!isShutdown) {
      try {
        ResourceWeakReference ref = (ResourceWeakReference) resourceReferenceQueue.remove();
        cleanupActiveReference(ref);

        // This section for testing only.
        DequeuedResourceCallback current = cb;
        if (current != null) {
          current.onResourceDequeued();
        }
        // End for testing only.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @VisibleForTesting
  void setDequeuedResourceCallback(DequeuedResourceCallback cb) {
    this.cb = cb;
  }

  @VisibleForTesting
  interface DequeuedResourceCallback {
    void onResourceDequeued();
  }

  /** shutdown中断线程，清除队列*/
 @VisibleForTesting
  void shutdown() {
    isShutdown = true;
    if (monitorClearedResourcesExecutor instanceof ExecutorService) {
      ExecutorService service = (ExecutorService) monitorClearedResourcesExecutor;
      Executors.shutdownAndAwaitTermination(service);
    }
  }
  /**弱引用监听对象，强引用保存真正的资源*/
  @VisibleForTesting
  static final class ResourceWeakReference extends WeakReference<EngineResource<?>> {
    @SuppressWarnings("WeakerAccess")
    @Synthetic
    final Key key;

    @SuppressWarnings("WeakerAccess")
    @Synthetic
    final boolean isCacheable;
    /**强引用，真正的资源*/
    @Nullable
    @SuppressWarnings("WeakerAccess")
    @Synthetic
    Resource<?> resource;

    @Synthetic
    @SuppressWarnings("WeakerAccess")
    ResourceWeakReference(
        @NonNull Key key,
        @NonNull EngineResource<?> referent,
        @NonNull ReferenceQueue<? super EngineResource<?>> queue,
        boolean isActiveResourceRetentionAllowed) {
      super(referent, queue);
      //保存key
      this.key = Preconditions.checkNotNull(key);
      //保存resource，强引用
      this.resource =
          referent.isMemoryCacheable() && isActiveResourceRetentionAllowed
              ? Preconditions.checkNotNull(referent.getResource())
              : null;
      isCacheable = referent.isMemoryCacheable();
    }
    /**清除强引用*/
    void reset() {
      resource = null;
      clear();
    }
  }
}
