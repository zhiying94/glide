package com.bumptech.glide.load.engine;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.DataFetcher.DataCallback;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Synthetic;
import java.util.Collections;

/**
 * Generates {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} from original source data
 * using registered {@link com.bumptech.glide.load.model.ModelLoader ModelLoaders} and the model
 * provided for the load.
 *
 * <p>Depending on the disk cache strategy, source data may first be written to disk and then loaded
 * from the cache file rather than returned directly.
 */
class SourceGenerator implements DataFetcherGenerator, DataFetcherGenerator.FetcherReadyCallback {
  private static final String TAG = "SourceGenerator";

  private final DecodeHelper<?> helper;
  private final FetcherReadyCallback cb;

  private int loadDataListIndex;
  private DataCacheGenerator sourceCacheGenerator;
  private Object dataToCache;
  private volatile ModelLoader.LoadData<?> loadData;
  private DataCacheKey originalKey;

  SourceGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this.helper = helper;
    this.cb = cb;
  }

  @Override
  public boolean startNext() {
    if (dataToCache != null) {
      Object data = dataToCache;
      dataToCache = null;
      //放入缓存
      cacheData(data);
    }
    //有缓存从缓存中获取
    if (sourceCacheGenerator != null && sourceCacheGenerator.startNext()) {
      return true;
    }
    sourceCacheGenerator = null;

    loadData = null;
    boolean started = false;
    while (!started && hasNextModelLoader()) {
      //获取一个 ModelLoad 加载器
      loadData = helper.getLoadData().get(loadDataListIndex++);
      if (loadData != null
          && (helper.getDiskCacheStrategy().isDataCacheable(loadData.fetcher.getDataSource())
              || helper.hasLoadPath(loadData.fetcher.getDataClass()))) {
        started = true;
        startNextLoad(loadData);
      }
    }
    return started;
  }

  private void startNextLoad(final LoadData<?> toStart) {
    //使用加载器中的 fetcher 根据优先级加载数据
    loadData.fetcher.loadData(
        helper.getPriority(),
        new DataCallback<Object>() {
          @Override
          public void onDataReady(@Nullable Object data) {
            if (isCurrentRequest(toStart)) {
              onDataReadyInternal(toStart, data);
            }
          }

          @Override
          public void onLoadFailed(@NonNull Exception e) {
            if (isCurrentRequest(toStart)) {
              onLoadFailedInternal(toStart, e);
            }
          }
        });
  }

  // We want reference equality explicitly to make sure we ignore results from old requests.
  @SuppressWarnings({"PMD.CompareObjectsWithEquals", "WeakerAccess"})
  @Synthetic
  boolean isCurrentRequest(LoadData<?> requestLoadData) {
    LoadData<?> currentLoadData = loadData;
    return currentLoadData != null && currentLoadData == requestLoadData;
  }
  /**判断是否还有下一个ModelLoader*/
  private boolean hasNextModelLoader() {
    return loadDataListIndex < helper.getLoadData().size();
  }

  private void cacheData(Object dataToCache) {
    long startTime = LogTime.getLogTime();
    try {
      Encoder<Object> encoder = helper.getSourceEncoder(dataToCache);
      DataCacheWriter<Object> writer =
          new DataCacheWriter<>(encoder, dataToCache, helper.getOptions());
      originalKey = new DataCacheKey(loadData.sourceKey, helper.getSignature());
      //存储原始数据
      //通过 StreamEncoder encode 写入文件
      helper.getDiskCache().put(originalKey, writer);
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(
            TAG,
            "Finished encoding source to cache"
                + ", key: "
                + originalKey
                + ", data: "
                + dataToCache
                + ", encoder: "
                + encoder
                + ", duration: "
                + LogTime.getElapsedMillis(startTime));
      }
    } finally {
      loadData.fetcher.cleanup();
    }

    sourceCacheGenerator =
        new DataCacheGenerator(Collections.singletonList(loadData.sourceKey), helper, this);
  }

  @Override
  public void cancel() {
    LoadData<?> local = loadData;
    if (local != null) {
      local.fetcher.cancel();
    }
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  void onDataReadyInternal(LoadData<?> loadData, Object data) {
    DiskCacheStrategy diskCacheStrategy = helper.getDiskCacheStrategy();
    if (data != null && diskCacheStrategy.isDataCacheable(loadData.fetcher.getDataSource())) {
      //1. 收到网络下载好的图片原始数据，赋值给成员变量 dataToCache
      dataToCache = data;
      // We might be being called back on someone else's thread. Before doing anything, we should
      // reschedule to get back onto Glide's thread.
      //如果可以缓存，执行cb.reschedule
      cb.reschedule();
    } else {
      //直接回调cb.onDataFetcherReady
      //2. 交给 EngineJob
      cb.onDataFetcherReady(
          loadData.sourceKey,
          data,
          loadData.fetcher,
          loadData.fetcher.getDataSource(),
          originalKey);
    }
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  void onLoadFailedInternal(LoadData<?> loadData, @NonNull Exception e) {
    cb.onDataFetcherFailed(originalKey, e, loadData.fetcher, loadData.fetcher.getDataSource());
  }

  @Override
  public void reschedule() {
    // We don't expect this to happen, although if we ever need it to we can delegate to our
    // callback.
    throw new UnsupportedOperationException();
  }

  // Called from source cache generator.
  @Override
  public void onDataFetcherReady(
      Key sourceKey, Object data, DataFetcher<?> fetcher, DataSource dataSource, Key attemptedKey) {
    // This data fetcher will be loading from a File and provide the wrong data source, so override
    // with the data source of the original fetcher
    cb.onDataFetcherReady(sourceKey, data, fetcher, loadData.fetcher.getDataSource(), sourceKey);
  }

  @Override
  public void onDataFetcherFailed(
      Key sourceKey, Exception e, DataFetcher<?> fetcher, DataSource dataSource) {
    cb.onDataFetcherFailed(sourceKey, e, fetcher, loadData.fetcher.getDataSource());
  }
}
