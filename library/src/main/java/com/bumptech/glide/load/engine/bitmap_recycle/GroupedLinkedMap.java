package com.bumptech.glide.load.engine.bitmap_recycle;

import androidx.annotation.Nullable;
import com.bumptech.glide.util.Synthetic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Similar to {@link java.util.LinkedHashMap} when access ordered except that it is access ordered
 * on groups of bitmaps rather than individual objects. The idea is to be able to find the LRU
 * bitmap size, rather than the LRU bitmap object. We can then remove bitmaps from the least
 * recently used size of bitmap when we need to reduce our cache size.
 *
 * <p>For the purposes of the LRU, we count gets for a particular size of bitmap as an access, even
 * if no bitmaps of that size are present. We do not count addition or removal of bitmaps as an
 * access.
 */
class GroupedLinkedMap<K extends Poolable, V> {
  /**头节点*/
  private final LinkedEntry<K, V> head = new LinkedEntry<>();
  /**存储key和entry的HashMap*/
  private final Map<K, LinkedEntry<K, V>> keyToEntry = new HashMap<>();

  public void put(K key, V value) {
    LinkedEntry<K, V> entry = keyToEntry.get(key);

    if (entry == null) {
      //创建结点
      entry = new LinkedEntry<>(key);
      //放到链表尾部
      makeTail(entry);
      //放到hashMap中
      keyToEntry.put(key, entry);
    } else {
      //keyPool的操作
      key.offer();
    }
    //放入entry数组中
    entry.add(value);
  }

  @Nullable
  public V get(K key) {
    //从HashMap中查找
    LinkedEntry<K, V> entry = keyToEntry.get(key);
    if (entry == null) {
      //如果不存在，创建结点，放到hashMap中
      entry = new LinkedEntry<>(key);
      keyToEntry.put(key, entry);
    } else {
      key.offer();
    }
    //放到链表头部
    makeHead(entry);
    //返回数组的最后一个
    return entry.removeLast();
  }
  /**移除队尾的元素*/
  @Nullable
  public V removeLast() {
    //获取队尾节点
    LinkedEntry<K, V> last = head.prev;
    //这一块的whild循环有意思
    while (!last.equals(head)) {
      //移除改节点数组的最后一个
      V removed = last.removeLast();
      if (removed != null) {
        return removed;
      } else {
        // We will clean up empty lru entries since they are likely to have been one off or
        // unusual sizes and
        // are not likely to be requested again so the gc thrash should be minimal. Doing so will
        // speed up our
        // removeLast operation in the future and prevent our linked list from growing to
        // arbitrarily large
        // sizes.
        //如果走到这里，说明last节点底下的数组为空，所以根本没有移除掉数据，第一件事就是干掉这个节点
        removeEntry(last);
        keyToEntry.remove(last.key);
        last.key.offer();
      }
      //走到这一步还是因为last节点底下的数组为空，继续探寻它的上一个节点，直到能return出去为止
      last = last.prev;
    }

    return null;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("GroupedLinkedMap( ");
    LinkedEntry<K, V> current = head.next;
    boolean hadAtLeastOneItem = false;
    while (!current.equals(head)) {
      hadAtLeastOneItem = true;
      sb.append('{').append(current.key).append(':').append(current.size()).append("}, ");
      current = current.next;
    }
    if (hadAtLeastOneItem) {
      sb.delete(sb.length() - 2, sb.length());
    }
    return sb.append(" )").toString();
  }
  /**设成链表头(其实就是head的下一个)*/
  // Make the entry the most recently used item.
  private void makeHead(LinkedEntry<K, V> entry) {
    removeEntry(entry);
    entry.prev = head;
    entry.next = head.next;
    updateEntry(entry);
  }
  /**设成链表尾(其实就是head的上一个)*/
  // Make the entry the least recently used item.
  private void makeTail(LinkedEntry<K, V> entry) {
    //把自己从链表中移除
    removeEntry(entry);
    //绑定自身的关系
    entry.prev = head.prev;
    entry.next = head;
    //绑定自身前后节点与自己的关系
    updateEntry(entry);
  }
  /**更新节点，把当前节点的上一个的next指向自己，下一个的perv指向自己，完成双向链表*/
  private static <K, V> void updateEntry(LinkedEntry<K, V> entry) {
    entry.next.prev = entry;
    entry.prev.next = entry;
  }
  /**删除当前节点，把自己上一个的next指向下一个，把自己下一个的prev指向上一个*/
  private static <K, V> void removeEntry(LinkedEntry<K, V> entry) {
    entry.prev.next = entry.next;
    entry.next.prev = entry.prev;
  }
  /**LinkedEntry是存入Map的节点，同时是一个双向链表，同时还是持有一个数组*/
  private static class LinkedEntry<K, V> {
    @Synthetic final K key;
    /**
     * value数组
     */
    private List<V> values;
    /**
     * 链表下一个节点
     */
    LinkedEntry<K, V> next;
    /**
     * 链表上一个节点
     */
    LinkedEntry<K, V> prev;

    // Used only for the first item in the list which we will treat specially and which will not
    // contain a value.
    LinkedEntry() {
      this(null);
    }

    LinkedEntry(K key) {
      next = prev = this;
      this.key = key;
    }
    /**移除数组的最后一个元素*/
    @Nullable
    public V removeLast() {
      final int valueSize = size();
      return valueSize > 0 ? values.remove(valueSize - 1) : null;
    }
    /**数组的长度*/
    public int size() {
      return values != null ? values.size() : 0;
    }
    /**添加到数组*/
    public void add(V value) {
      if (values == null) {
        values = new ArrayList<>();
      }
      values.add(value);
    }
  }
}
