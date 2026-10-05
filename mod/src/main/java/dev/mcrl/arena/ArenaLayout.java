package dev.mcrl.arena;

/**
 * A generated arena in local coordinates: y=0 is the bedrock floor, the interior is
 * 1..sizeX x 1..sizeY x 1..sizeZ, and the outer x/z ring is bedrock for every y.
 */
public final class ArenaLayout {
    private final StageConfig config;
    private final Cell[] cells;

    private ArenaLayout(StageConfig config) {
        this.config = config;
        this.cells = new Cell[config.width() * config.height() * config.depth()];
    }

    static ArenaLayout empty(StageConfig config) {
        ArenaLayout a = new ArenaLayout(config);
        for (int y = 0; y < a.height(); y++)
            for (int z = 0; z < a.depth(); z++)
                for (int x = 0; x < a.width(); x++) {
                    boolean shell = y == 0 || x == 0 || z == 0 || x == a.width() - 1 || z == a.depth() - 1;
                    a.set(x, y, z, shell ? Cell.BEDROCK : Cell.AIR);
                }
        return a;
    }

    public StageConfig config() { return config; }
    public int width() { return config.width(); }
    public int height() { return config.height(); }
    public int depth() { return config.depth(); }

    public Cell get(int x, int y, int z) { return cells[index(x, y, z)]; }

    void set(int x, int y, int z, Cell cell) { cells[index(x, y, z)] = cell; }

    /** Feet position of the agent at spawn. */
    public int spawnX() { return 1 + config.sizeX() / 2; }
    public int spawnY() { return config.sizeY() - 1; }
    public int spawnZ() { return 1 + config.sizeZ() / 2; }

    /** Lower half of the interior uses deepslate variants. */
    public boolean isDeep(int y) { return y >= 1 && y <= config.sizeY() / 2; }

    public boolean inSpawnPocket(int x, int y, int z) {
        return Math.abs(x - spawnX()) <= 1 && Math.abs(z - spawnZ()) <= 1 && y >= spawnY() && y <= config.sizeY();
    }

    public boolean underSpawnPocket(int x, int y, int z) {
        return Math.abs(x - spawnX()) <= 1 && Math.abs(z - spawnZ()) <= 1 && y == spawnY() - 1;
    }

    public int count(Cell cell) {
        int n = 0;
        for (Cell c : cells) if (c == cell) n++;
        return n;
    }

    public Cell[] snapshot() { return cells.clone(); }

    private int index(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width() || y >= height() || z >= depth()) {
            throw new IndexOutOfBoundsException("(" + x + "," + y + "," + z + ") outside arena");
        }
        return (y * depth() + z) * width() + x;
    }
}
