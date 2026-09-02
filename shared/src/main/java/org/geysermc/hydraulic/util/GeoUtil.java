package org.geysermc.hydraulic.util;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Description;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.Cubes;

import java.util.ArrayList;
import java.util.List;

public class GeoUtil {
    private static final String FORMAT_VERSION = "1.16.0";
    private static final float[] ELEMENT_OFFSET = new float[] { 8, 0, 8 };
    private static final int SCALE = 16;

    /**
     * Create a model entity from a voxel shape with default texture size 16.
     *
     * @param shape the voxel shape
     * @param geoName the name of the geometry
     * @return the created model entity
     */
    public static ModelEntity fromShape(VoxelShape shape, String geoName) {
        return fromShape(shape, geoName, false, 16, 16);
    }

    /**
     * Create a model entity from a voxel shape. Retained for callers that
     * don't yet know the texture dimensions; new call sites should prefer
     * the overload that accepts explicit {@code textureWidth}/{@code textureHeight}.
     *
     * @param shape the voxel shape
     * @param geoName the name of the geometry
     * @param groupBones whether to attach every bone under the first bone
     * @return the created model entity
     */
    public static ModelEntity fromShape(VoxelShape shape, String geoName, boolean groupBones) {
        return fromShape(shape, geoName, groupBones, 16, 16);
    }

    /**
     * Converts a collision silhouette to Bedrock geometry. Entity fallbacks
     * can parent every box to the first bone so their generic animation moves
     * one coherent model; block geometries deliberately keep independent
     * bones for their existing static output.
     *
     * <p>Texture dimensions should match the actual texture PNG when one is
     * bound to this geometry. Passing the wrong size causes Bedrock to clamp
     * or remap UV coordinates, producing visibly distorted textures.</p>
     *
     * @param shape the voxel shape
     * @param geoName the name of the geometry
     * @param groupBones whether to attach every bone under the first bone
     * @param textureWidth width of the bound texture in pixels
     * @param textureHeight height of the bound texture in pixels
     * @return the created model entity
     */
    public static ModelEntity fromShape(VoxelShape shape, String geoName, boolean groupBones, int textureWidth, int textureHeight) {
        ModelEntity modelEntity = new ModelEntity();
        modelEntity.formatVersion(FORMAT_VERSION);

        Geometry geometry = new Geometry();

        Description description = new Description();
        description.identifier(geoName);
        description.textureWidth(textureWidth);
        description.textureHeight(textureHeight);
        AABB bounds = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        description.visibleBoundsWidth(Math.max(2, (float) Math.max(bounds.getXsize(), bounds.getZsize()) * 1.2f));
        description.visibleBoundsHeight(Math.max(2, (float) bounds.getYsize() * 1.2f));
        description.visibleBoundsOffset(new float[] { 0.0f, (float) ((bounds.minY + bounds.maxY) / 2), 0.0f });
        geometry.description(description);

        List<Bones> bones = new ArrayList<>();

        for (AABB box : shape.toAabbs()) {
            float[] from = new float[] { (float) box.minX * SCALE, (float) box.minY * SCALE, (float) box.minZ * SCALE };
            float[] to = new float[] { (float) box.maxX * SCALE, (float) box.maxY * SCALE, (float) box.maxZ * SCALE };

            Bones bone = new Bones();
            bone.name((groupBones ? "hydraulic_hitbox_" : "bone_") + bones.size());
            if (groupBones && !bones.isEmpty()) {
                bone.parent("hydraulic_hitbox_0");
            }
            bone.pivot(new float[] { ELEMENT_OFFSET[0], ELEMENT_OFFSET[1], -ELEMENT_OFFSET[2] });

            Cubes cube = new Cubes();
            cube.origin(new float[] { ELEMENT_OFFSET[0] - to[0], from[1], from[2] - ELEMENT_OFFSET[2] });
            cube.size(new float[] { to[0] - from[0], to[1] - from[1], to[2] - from[2] });

            bone.cubes(List.of(cube));
            bones.add(bone);
        }

        geometry.bones(bones);

        modelEntity.geometry(List.of(geometry));

        return modelEntity;
    }

    /**
     * Create an empty model entity with default texture size 16.
     *
     * @param geoName the name of the geometry
     * @return the created model entity
     */
    public static ModelEntity empty(String geoName) {
        return empty(geoName, 16, 16);
    }

    /**
     * Create an empty model entity. Prefer the overload that accepts explicit
     * texture dimensions when the geometry will be bound to a real texture.
     *
     * @param geoName the name of the geometry
     * @param textureWidth width of the bound texture in pixels
     * @param textureHeight height of the bound texture in pixels
     * @return the created model entity
     */
    public static ModelEntity empty(String geoName, int textureWidth, int textureHeight) {
        ModelEntity modelEntity = new ModelEntity();
        modelEntity.formatVersion(FORMAT_VERSION);

        Geometry geometry = new Geometry();

        Description description = new Description();
        description.identifier(geoName);
        description.textureWidth(textureWidth);
        description.textureHeight(textureHeight);
        description.visibleBoundsWidth(2);
        description.visibleBoundsHeight(2);
        description.visibleBoundsOffset(new float[] { 0.0f, 0.25f, 0.0f });
        geometry.description(description);

        modelEntity.geometry(List.of(geometry));

        return modelEntity;
    }
}
