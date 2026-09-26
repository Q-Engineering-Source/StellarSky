package stellarium.client.ring.cloud;

/** Immutable CPU mesh settings. Thickness is one of the eight canonical atlas slices. */
public record CloudGeometrySettings(double cellSizeBlocks, double thicknessBlocks, int visibleCellRadius) {
    public static final int MAX_VISIBLE_CELL_RADIUS = 64;
    public static final CloudGeometrySettings DEFAULT = new CloudGeometrySettings(12.0D, 4.0D, 48);

    /** The configured height belongs to an effective layer, not an internal atlas slice. */
    public static CloudGeometrySettings forLayers(double cellSizeBlocks, double layerHeightBlocks,
                                                   int layers, int visibleCellRadius) {
        CloudFieldSettings.requireLayers(layers);
        return new CloudGeometrySettings(cellSizeBlocks,
                layerHeightBlocks * layers / CloudColumn.LAYERS, visibleCellRadius);
    }

    public CloudGeometrySettings {
        requireFinite("cellSizeBlocks", cellSizeBlocks);
        requireFinite("thicknessBlocks", thicknessBlocks);
        if (cellSizeBlocks <= 0.0D || thicknessBlocks <= 0.0D) {
            throw new IllegalArgumentException("Cloud cell size and thickness must be greater than zero");
        }
        if (visibleCellRadius < 0 || visibleCellRadius > MAX_VISIBLE_CELL_RADIUS) {
            throw new IllegalArgumentException("visibleCellRadius must be within [0, "
                    + MAX_VISIBLE_CELL_RADIUS + "]");
        }
        // Mesh coordinates are deliberately small, but still become floats at
        // the upload boundary. Reject settings that would manufacture Infinity.
        if (cellSizeBlocks * (visibleCellRadius + 1.0D) > Float.MAX_VALUE
                || thicknessBlocks > Float.MAX_VALUE) {
            throw new IllegalArgumentException("Cloud geometry cannot be represented by finite float vertices");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    public double voxelHeightBlocks() {
        return thicknessBlocks;
    }
}
