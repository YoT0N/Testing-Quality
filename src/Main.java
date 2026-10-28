import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/*
  @author   george
  @project   Default (Template) Project
  @class  ${NAME}
  @version  1.0.0
  @since 06/09/2026 - 20.13
*/

// Усі унікальні слова лежать підряд в одному byte[] (pool),
// слово k займає pool[start[k] .. start[k+1]).
// Хеш-таблиця (table) зберігає id+1, 0 = порожня комірка.
final int TOP = 30;

byte[] pool = new byte[1 << 16];
int poolLen = 0;
int[] start = new int[1 << 13];
int[] count = new int[1 << 13];
int ids = 0;
int[] table = new int[1 << 14];

byte[] cur = new byte[64];   // поточне слово
int curLen = 0;

long allocated() {
    return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
            .getThreadAllocatedBytes(Thread.currentThread().threadId());
}

int lenOf(int id) {
    return (id + 1 < ids ? start[id + 1] : poolLen) - start[id];
}

boolean same(int id) {
    if (lenOf(id) != curLen) return false;
    int s = start[id];
    for (int i = 0; i < curLen; i++) {
        if (pool[s + i] != cur[i]) return false;
    }
    return true;
}

void addWord(int h) {
    h ^= (h >>> 16);
    int mask = table.length - 1;
    int idx = h & mask;
    int id;
    while ((id = table[idx]) != 0) {
        id--;
        if (same(id)) {
            count[id]++;
            return;
        }
        idx = (idx + 1) & mask;
    }
    // нове слово
    if (ids == start.length) {
        start = Arrays.copyOf(start, ids * 2);
        count = Arrays.copyOf(count, ids * 2);
    }
    if (poolLen + curLen > pool.length) {
        pool = Arrays.copyOf(pool, Math.max(pool.length * 2, poolLen + curLen));
    }
    System.arraycopy(cur, 0, pool, poolLen, curLen);
    start[ids] = poolLen;
    poolLen += curLen;
    count[ids] = 1;
    table[idx] = ++ids;
    if (ids * 2 > table.length) rehash();
}

void rehash() {
    int[] nt = new int[table.length * 2];
    int mask = nt.length - 1;
    for (int id = 0; id < ids; id++) {
        int h = 0;
        int s = start[id], e = s + lenOf(id);
        for (int p = s; p < e; p++) h = h * 31 + pool[p];
        h ^= (h >>> 16);
        int idx = h & mask;
        while (nt[idx] != 0) idx = (idx + 1) & mask;
        nt[idx] = id + 1;
    }
    table = nt;
}

int compareWords(int a, int b) {
    int la = lenOf(a), lb = lenOf(b);
    int sa = start[a], sb = start[b];
    int n = Math.min(la, lb);
    for (int i = 0; i < n; i++) {
        int d = pool[sa + i] - pool[sb + i];
        if (d != 0) return d;
    }
    return la - lb;
}

boolean better(int a, int b) {
    if (count[a] != count[b]) return count[a] > count[b];
    return compareWords(a, b) > 0;
}

void main() throws IOException {
    LocalDateTime startTime = LocalDateTime.now();

    long memStart = allocated();
    long t0 = System.nanoTime();

    byte[] buf = new byte[1 << 14];
    int h = 0;
    try (InputStream in = Files.newInputStream(Paths.get("src/txt/harry.txt"))) {
        int n;
        while ((n = in.read(buf)) != -1) {
            for (int i = 0; i < n; i++) {
                int b = buf[i];
                if (b >= 'A' && b <= 'Z') b += 32;
                if (b >= 'a' && b <= 'z') {
                    if (curLen == cur.length) cur = Arrays.copyOf(cur, curLen * 2);
                    cur[curLen++] = (byte) b;
                    h = h * 31 + b;
                } else if (curLen > 0) {
                    addWord(h);
                    curLen = 0;
                    h = 0;
                }
            }
        }
        if (curLen > 0) addWord(h);
    }

    // топ-30: масив з 30 id, впорядкований від кращого до гіршого
    int[] top = new int[TOP];
    int topSize = 0;
    for (int id = 0; id < ids; id++) {
        if (topSize == TOP && !better(id, top[TOP - 1])) continue;
        int pos = topSize < TOP ? topSize++ : TOP - 1;
        while (pos > 0 && better(id, top[pos - 1])) {
            top[pos] = top[pos - 1];
            pos--;
        }
        top[pos] = id;
    }

    for (int i = 0; i < topSize; i++) {
        int id = top[i];
        System.out.println(new String(pool, start[id], lenOf(id), StandardCharsets.ISO_8859_1)
                + " " + count[id]);
    }

    LocalDateTime finish = LocalDateTime.now();

    System.out.println("------");
    System.out.println((System.nanoTime() - t0) / 1_000_000 + " ms");
    System.out.println(ChronoUnit.MILLIS.between(startTime, finish) + " ms");
    System.out.printf("Виділено пам'яті: %.1f МБ%n", (allocated() - memStart) / 1048576.0);
    System.out.println("Унікальних слів: " + ids);
}