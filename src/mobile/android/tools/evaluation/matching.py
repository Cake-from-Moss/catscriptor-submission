"""Точное максимальное взвешенное паросочетание на целых числах."""


def maximum_assignment(weights: list[list[int]]) -> list[tuple[int, int]]:
    """Венгерский алгоритм O(n³); нулевые и фиктивные пары не возвращаются."""
    rows = len(weights)
    cols = len(weights[0]) if rows else 0
    size = max(rows, cols)
    if not rows or not cols:
        return []
    # Минимизируем отрицательные веса. Python int сохраняет большие миллисекунды.
    cost = [[-weights[i][j] if i < rows and j < cols else 0
             for j in range(size)] for i in range(size)]
    u, v, p, way = ([0] * (size + 1) for _ in range(4))
    for i in range(1, size + 1):
        p[0] = i
        j0 = 0
        minimum = [None] * (size + 1)
        used = [False] * (size + 1)
        while True:
            used[j0] = True
            i0 = p[j0]
            delta, j1 = None, 0
            for j in range(1, size + 1):
                if used[j]:
                    continue
                current = cost[i0 - 1][j - 1] - u[i0] - v[j]
                if minimum[j] is None or current < minimum[j]:
                    minimum[j], way[j] = current, j0
                if delta is None or minimum[j] < delta:
                    delta, j1 = minimum[j], j
            for j in range(size + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                elif minimum[j] is not None:
                    minimum[j] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while j0:
            j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
    return sorted((p[j] - 1, j - 1) for j in range(1, size + 1)
                  if 0 < p[j] <= rows and j <= cols and weights[p[j] - 1][j - 1] > 0)
