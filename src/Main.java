import java.io.FileInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

// v4: оптимізація ШВИДКОДІЇ (пам'яттю свідомо жертвуємо)
final int TOP = 30;
final int WARM_RUNS = 30; // 0 = не робити замірів після прогріву

byte[] data; // весь файл
final byte[] MAP = buildMap(); // таблиця: байт -> мала літера a-z або 0 (не літера)

int[] startOf = new int[1 << 13]; // де слово починається в data
int[] lenOf = new int[1 << 13]; // довжина слова
int[] hashOf = new int[1 << 13]; // збережений хеш слова
int[] count = new int[1 << 13]; // частота
int ids = 0;
int[] table = new int[1 << 15];

long tRead;
long tCount;

long allocated() {
    return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
            .getThreadAllocatedBytes(Thread.currentThread().threadId());
}

byte[] buildMap() {
    byte[] m = new byte[256];
    for (int c = 'a'; c <= 'z'; c++) m[c] = (byte) c;
    for (int c = 'A'; c <= 'Z'; c++) m[c] = (byte) (c + 32);
    return m;
}

void addWord(int s, int len, int h) {
    h ^= (h >>> 16);
    int mask = table.length - 1;
    int idx = h & mask;
    int id;
    while ((id = table[idx]) != 0) {
        id--;
        // спочатку дешеві перевірки (хеш, довжина), байти порівнюємо лише якщо вони збіглися
        if (hashOf[id] == h && lenOf[id] == len
                && Arrays.equals(data, startOf[id], startOf[id] + len, data, s, s + len)) {
            count[id]++;
            return;
        }
        idx = (idx + 1) & mask;
    }
    if (ids == startOf.length) {
        int cap = ids * 2;
        startOf = Arrays.copyOf(startOf, cap);
        lenOf = Arrays.copyOf(lenOf, cap);
        hashOf = Arrays.copyOf(hashOf, cap);
        count = Arrays.copyOf(count, cap);
    }
    startOf[ids] = s;
    lenOf[ids] = len;
    hashOf[ids] = h;
    count[ids] = 1;
    table[idx] = ++ids;
    if (ids * 2 > table.length) rehash();
}

void rehash() {
    int[] nt = new int[table.length * 2];
    int mask = nt.length - 1;
    for (int id = 0; id < ids; id++) {
        int idx = hashOf[id] & mask;
        while (nt[idx] != 0) idx = (idx + 1) & mask;
        nt[idx] = id + 1;
    }
    table = nt;
}

void reset() {
    ids = 0;
    Arrays.fill(table, 0);
}

void process() throws IOException {
    long a = System.nanoTime();
    // FileInputStream замість Files.*: не вантажить важкий NIO-ланцюжок, у холодному JVM ~5 мс виграшу
    try (FileInputStream in = new FileInputStream("src/txt/harry.txt")) {
        data = in.readAllBytes();
    }
    long b = System.nanoTime();

    byte[] d = data, map = MAP;
    int n = d.length;
    int i = 0;
    while (i < n) { // ОДИН прохід: фільтр + lowercase + хеш + підрахунок
        int c = map[d[i] & 0xFF];
        if (c == 0) { i++; continue; }
        int s = i, h = 0;
        do {
            d[i] = (byte) c; // слово в data лишається малими літерами
            h = h * 31 + c;
            i++;
        } while (i < n && (c = map[d[i] & 0xFF]) != 0);
        addWord(s, i - s, h);
    }
    long e = System.nanoTime();
    tRead = b - a;
    tCount = e - b;
}

int compareWords(int a, int b) {
    return Arrays.compare(data, startOf[a], startOf[a] + lenOf[a],
            data, startOf[b], startOf[b] + lenOf[b]);
}

boolean better(int a, int b) {
    if (count[a] != count[b]) return count[a] > count[b];
    return compareWords(a, b) > 0;
}

int[] topWords() {
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
    return top;
}

void main() throws IOException {
    long memStart = allocated();  // перший виклик вантажить JMX - у час не входить
    long t0 = System.nanoTime();

    process();
    int[] top = topWords();

    StringBuilder sb = new StringBuilder(512); // один виклик print замість 30 println
    for (int id : top) {
        for (int k = 0; k < lenOf[id]; k++) sb.append((char) data[startOf[id] + k]);
        sb.append(' ').append(count[id]).append('\n');
    }
    System.out.print(sb);

    long t1 = System.nanoTime();
    long mem = allocated() - memStart;

    System.out.println("------");
    System.out.printf("Час: %.1f мс (читання %.1f, обробка %.1f)%n",
            (t1 - t0) / 1e6, tRead / 1e6, tCount / 1e6);
    System.out.printf("Виділено пам'яті: %.1f МБ%n", mem / 1048576.0);
    System.out.println("Унікальних слів: " + ids);

    if (WARM_RUNS > 0) {
        double[] t = new double[WARM_RUNS];
        for (int r = 0; r < WARM_RUNS; r++) {
            reset();
            long s = System.nanoTime();
            process();
            topWords();
            t[r] = (System.nanoTime() - s) / 1e6;
        }
        Arrays.sort(t);
        System.out.printf("Після прогріву (%d запусків): мін %.2f мс, медіана %.2f мс%n",
                WARM_RUNS, t[0], t[WARM_RUNS / 2]);
    }
}