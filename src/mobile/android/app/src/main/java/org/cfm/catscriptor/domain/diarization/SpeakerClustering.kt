package org.cfm.catscriptor.domain.diarization

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Параметры кластеризации эмбеддингов окон. Основа — рецепт 3D-Speaker для CAM++
 * (`SpectralCluster`: min_pnum 6; `merge_by_cos` 0,8). Долю соседей pval пришлось поднять с 0,022:
 * на 11-минутной записи с 4 спикерами 0,022 давало 6–7 спикеров. Перебор на 6 записях с известным
 * числом спикеров: все верны при pval от 0,07 до 0,25, выбрана середина плато (docs/diarization.md).
 *
 * @property pruneFraction доля самых близких соседей, которые остаются в строке матрицы сходства (p-pruning).
 * @property minPruneNeighbors минимум соседей после pruning для коротких записей.
 * @property maxSpeakers верхняя граница числа спикеров при поиске eigengap.
 * @property minSpectralPoints меньше окон — агломеративная кластеризация: у eigengap мало данных.
 * @property ahcSimilarity порог средней косинусной близости для агломеративного режима. CAM++ даёт для
 *   одного человека в разных репликах близость 0,4–0,56, а для разных людей 0,2–0,4; прежние 0,5 дробили
 *   один голос на нескольких «спикеров» в коротких записях (диалог 12 с давал 3 вместо 2). Верное число
 *   спикеров на всех записях набора, где работает этот режим, получается при 0,35–0,40; выбрана середина.
 * @property mergeSimilarity кластеры с более близкими центроидами считаются одним голосом.
 * @property minSpeakerMs кластер с меньшей суммарной речью не считается отдельным спикером:
 *   его окна переходят к ближайшему крупному кластеру. Это главный барьер против «лишних» спикеров.
 * @property maxPoints предел окон для спектрального разложения (O(n³)); остальные окна
 *   прикрепляются к ближайшему центроиду.
 */
data class SpeakerClusteringSettings(
    val pruneFraction: Double = 0.12,
    val minPruneNeighbors: Int = 6,
    val maxSpeakers: Int = 10,
    val minSpectralPoints: Int = 10,
    val ahcSimilarity: Double = 0.375,
    val mergeSimilarity: Double = 0.8,
    val minSpeakerMs: Long = 2_000,
    val maxPoints: Int = 600,
    val kmeansRestarts: Int = 10,
    val kmeansIterations: Int = 100,
) {
    init {
        require(pruneFraction in 0.0..1.0 && minPruneNeighbors >= 1 && maxSpeakers >= 1)
        require(minSpectralPoints >= 2 && maxPoints >= minSpectralPoints && minSpeakerMs >= 0)
        require(kmeansRestarts >= 1 && kmeansIterations >= 1)
    }
}

/**
 * Кластеризация эмбеддингов голоса: число спикеров определяется автоматически или задаётся
 * пользователем (настройка «Число спикеров»). Детерминирована: k-means
 * инициализируется фиксированным зерном, поэтому один и тот же файл даёт одну и ту же разметку.
 */
class SpeakerClustering(private val settings: SpeakerClusteringSettings = SpeakerClusteringSettings()) {

    /**
     * @param embeddings эмбеддинги окон (одинаковой размерности, ненулевые).
     * @param durationsMs сколько речи «представляет» каждое окно; нужно для отсева мелких кластеров.
     * @param targetSpeakers заданное число спикеров; null — автоопределение. При заданном числе
     *   eigengap и порог AHC не используются, а склейка похожих и отсев мелких кластеров
     *   отключены: они уменьшили бы число спикеров вопреки выбору пользователя. Кластеров
     *   получится меньше, только если окон меньше заданного числа.
     * @return метку кластера для каждого окна; метки 0..k-1 без пропусков.
     */
    fun cluster(embeddings: List<FloatArray>, durationsMs: List<Long>, targetSpeakers: Int? = null): IntArray {
        require(embeddings.size == durationsMs.size)
        require(targetSpeakers == null || targetSpeakers >= 1) { "Число спикеров должно быть положительным" }
        if (embeddings.isEmpty()) return IntArray(0)
        val points = embeddings.map(::normalize)
        require(points.all { it.size == points[0].size }) { "Размерность эмбеддингов различается" }
        if (points.size == 1) return IntArray(1)
        val target = targetSpeakers?.coerceAtMost(points.size)
        if (target == 1) return IntArray(points.size)

        val labels = if (points.size <= settings.maxPoints) {
            initial(points, target)
        } else {
            // Равномерная выборка по времени, затем прикрепление остальных окон к центроидам.
            val stride = points.size.toDouble() / settings.maxPoints
            val sample = (0 until settings.maxPoints).map { (it * stride).toInt() }
            val sampled = initial(sample.map { points[it] }, target)
            val centroids = centroids(sample.map { points[it] }, sampled)
            IntArray(points.size) { i -> nearest(points[i], centroids) }
        }
        if (target == null) {
            mergeSimilar(points, labels)
            absorbMinor(points, durationsMs, labels)
        }
        return compact(labels)
    }

    private fun initial(points: List<DoubleArray>, target: Int?): IntArray =
        if (points.size < settings.minSpectralPoints) agglomerative(points, target) else spectral(points, target)

    /**
     * Средняя связь по косинусу. Без [target] остановка, когда лучшая пара ближе порога не найдётся;
     * с [target] — когда кластеров осталось ровно столько.
     */
    private fun agglomerative(points: List<DoubleArray>, target: Int?): IntArray {
        val clusters = points.indices.map { mutableListOf(it) }.toMutableList()
        val similarity = Array(points.size) { i -> DoubleArray(points.size) { j -> dot(points[i], points[j]) } }
        while (clusters.size > (target ?: 1)) {
            var best = Double.NEGATIVE_INFINITY
            var bestI = -1
            var bestJ = -1
            for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                var sum = 0.0
                for (a in clusters[i]) for (b in clusters[j]) sum += similarity[a][b]
                val mean = sum / (clusters[i].size * clusters[j].size)
                if (mean > best) { best = mean; bestI = i; bestJ = j }
            }
            if (target == null && best < settings.ahcSimilarity) break
            clusters[bestI].addAll(clusters[bestJ])
            clusters.removeAt(bestJ)
        }
        val labels = IntArray(points.size)
        clusters.forEachIndexed { label, members -> members.forEach { labels[it] = label } }
        return labels
    }

    /**
     * Спектральная кластеризация 3D-Speaker: матрица косинусного сходства → p-pruning
     * (в каждой строке остаются ближайшие соседи) → симметризация → лапласиан L = D − A →
     * число спикеров по максимальному разрыву собственных значений (или заданное [target]) →
     * k-means по k первым векторам.
     */
    private fun spectral(points: List<DoubleArray>, target: Int?): IntArray {
        val n = points.size
        val affinity = Array(n) { i -> DoubleArray(n) { j -> dot(points[i], points[j]) } }
        val keep = maxOf(settings.minPruneNeighbors, n - ((1 - settings.pruneFraction) * n).toInt()).coerceAtMost(n)
        for (row in affinity) {
            val threshold = row.sortedArrayDescending()[keep - 1]
            // Ровно keep значений: все строго большие порога, затем равные ему по порядку индексов.
            var ties = keep - row.count { it > threshold }
            for (j in row.indices) {
                when {
                    row[j] > threshold -> Unit
                    row[j] == threshold && ties > 0 -> ties--
                    else -> row[j] = 0.0
                }
            }
        }
        val laplacian = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            var degree = 0.0
            for (j in 0 until n) {
                if (i == j) continue
                val a = 0.5 * (affinity[i][j] + affinity[j][i])
                laplacian[i][j] = -a
                degree += kotlin.math.abs(a)
            }
            laplacian[i][i] = degree
        }
        val eigen = SymmetricEigen(laplacian)
        val k = target ?: run {
            val maxK = minOf(settings.maxSpeakers, n - 1)
            var best = 1
            var bestGap = Double.NEGATIVE_INFINITY
            for (candidate in 1..maxK) {
                val gap = eigen.values[candidate] - eigen.values[candidate - 1]
                if (gap > bestGap) { bestGap = gap; best = candidate }
            }
            best
        }
        if (k == 1) return IntArray(n)
        val features = List(n) { i -> DoubleArray(k) { j -> eigen.vectors[i][j] } }
        return kmeans(features, k)
    }

    /** k-means++ с фиксированным зерном; из нескольких запусков берётся с минимальной инерцией. */
    private fun kmeans(features: List<DoubleArray>, k: Int): IntArray {
        val random = Random(KMEANS_SEED)
        var bestLabels = IntArray(features.size)
        var bestInertia = Double.POSITIVE_INFINITY
        repeat(settings.kmeansRestarts) {
            val centers = seed(features, k, random)
            val labels = IntArray(features.size) { -1 }
            for (iteration in 0 until settings.kmeansIterations) {
                var changed = false
                for (i in features.indices) {
                    val label = centers.indices.minBy { squared(features[i], centers[it]) }
                    if (label != labels[i]) { labels[i] = label; changed = true }
                }
                if (!changed) break
                for (c in centers.indices) {
                    val members = features.indices.filter { labels[it] == c }
                    if (members.isEmpty()) continue
                    centers[c] = DoubleArray(features[0].size) { d -> members.sumOf { features[it][d] } / members.size }
                }
            }
            val inertia = features.indices.sumOf { squared(features[it], centers[labels[it]]) }
            if (inertia < bestInertia) { bestInertia = inertia; bestLabels = labels.copyOf() }
        }
        return bestLabels
    }

    private fun seed(features: List<DoubleArray>, k: Int, random: Random): Array<DoubleArray> {
        val centers = ArrayList<DoubleArray>(k)
        centers += features[random.nextInt(features.size)].copyOf()
        while (centers.size < k) {
            val distances = features.map { f -> centers.minOf { squared(f, it) } }
            val total = distances.sum()
            if (total == 0.0) {
                centers += features[random.nextInt(features.size)].copyOf()
                continue
            }
            var target = random.nextDouble() * total
            var chosen = features.lastIndex
            for (i in distances.indices) {
                target -= distances[i]
                if (target <= 0) { chosen = i; break }
            }
            centers += features[chosen].copyOf()
        }
        return centers.toTypedArray()
    }

    /** Склеивает пары кластеров, чьи центроиды ближе [SpeakerClusteringSettings.mergeSimilarity]. */
    private fun mergeSimilar(points: List<DoubleArray>, labels: IntArray) {
        while (true) {
            val ids = labels.distinct()
            if (ids.size < 2) return
            val centroids = ids.associateWith { id -> centroid(points, labels, id) }
            var best = Double.NEGATIVE_INFINITY
            var pair: Pair<Int, Int>? = null
            for (a in ids.indices) for (b in a + 1 until ids.size) {
                val similarity = dot(centroids.getValue(ids[a]), centroids.getValue(ids[b]))
                if (similarity > best) { best = similarity; pair = ids[a] to ids[b] }
            }
            if (best < settings.mergeSimilarity) return
            val (keep, drop) = pair!!
            for (i in labels.indices) if (labels[i] == drop) labels[i] = keep
        }
    }

    /** Окна кластеров с речью меньше [SpeakerClusteringSettings.minSpeakerMs] переходят к ближайшему крупному. */
    private fun absorbMinor(points: List<DoubleArray>, durationsMs: List<Long>, labels: IntArray) {
        val speech = HashMap<Int, Long>()
        labels.forEachIndexed { i, label -> speech[label] = (speech[label] ?: 0L) + durationsMs[i] }
        val major = speech.filterValues { it >= settings.minSpeakerMs }.keys.ifEmpty {
            setOf(speech.maxBy { it.value }.key)
        }
        if (major.size == speech.size) return
        val ids = major.sorted()
        val centroids = ids.map { centroid(points, labels, it) }
        for (i in labels.indices) {
            if (labels[i] !in major) labels[i] = ids[nearest(points[i], centroids)]
        }
    }

    private fun centroids(points: List<DoubleArray>, labels: IntArray): List<DoubleArray> =
        labels.distinct().sorted().let { ids ->
            val mapping = ids.withIndex().associate { it.value to it.index }
            for (i in labels.indices) labels[i] = mapping.getValue(labels[i])
            ids.indices.map { centroid(points, labels, it) }
        }

    private fun centroid(points: List<DoubleArray>, labels: IntArray, id: Int): DoubleArray {
        val sum = DoubleArray(points[0].size)
        for (i in labels.indices) if (labels[i] == id) for (d in sum.indices) sum[d] += points[i][d]
        // Взаимно гасящиеся эмбеддинги дают нулевую сумму: такой центроид ни на что не похож (косинус 0),
        // но не должен ронять всю диаризацию.
        return if (sum.all { it == 0.0 }) sum else normalize(sum)
    }

    private fun nearest(point: DoubleArray, centroids: List<DoubleArray>): Int =
        centroids.indices.maxBy { dot(point, centroids[it]) }

    /** Метки по порядку первого появления: 0, 1, 2… */
    private fun compact(labels: IntArray): IntArray {
        val mapping = LinkedHashMap<Int, Int>()
        return IntArray(labels.size) { mapping.getOrPut(labels[it]) { mapping.size } }
    }

    private fun normalize(vector: FloatArray): DoubleArray {
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Некорректный эмбеддинг" }
        return normalize(DoubleArray(vector.size) { vector[it].toDouble() })
    }

    private fun normalize(vector: DoubleArray): DoubleArray {
        val norm = sqrt(vector.sumOf { it * it })
        require(norm > 0) { "Нулевой эмбеддинг" }
        return DoubleArray(vector.size) { vector[it] / norm }
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun squared(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; sum += d * d }
        return sum
    }

    private companion object {
        const val KMEANS_SEED = 20261107
    }
}
