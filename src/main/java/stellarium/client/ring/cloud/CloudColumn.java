package stellarium.client.ring.cloud;

/** Reusable eight-sample canonical cloud column scratch; it owns no world/cache state. */
final class CloudColumn {
    static final int LAYERS = 8;
    final int[] argb = new int[LAYERS];
    final int[] reduced = new int[LAYERS];
    final double[] scores = new double[LAYERS];
    double worldX, worldZ, sampleX, sampleZ, regional, centerNoise, halfNoise, crack, filterMinimum;
}
