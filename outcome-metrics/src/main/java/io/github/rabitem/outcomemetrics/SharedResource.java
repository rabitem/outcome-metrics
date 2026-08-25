package io.github.rabitem.outcomemetrics;

import io.micrometer.common.KeyValues;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Standardized attribution tags for operations on shared or borrowed resources.
 *
 * <p>In multi-tenant systems, failures on borrowed or pooled resources are misattributed when only
 * the consumer's context is tagged. This value type emits a fixed, closed tag bundle so attribution
 * dashboards are shareable across teams:
 *
 * <ul>
 * <li>{@code resource} — closed resource type, e.g. {@code instructor}</li>
 * <li>{@code relationship} — {@code owned} | {@code borrowed} | {@code pooled} (fixed by factory)</li>
 * <li>{@code consumer_tier} — the consuming tenant's tier, never a tenant id</li>
 * <li>{@code owner_tier} — the owning tenant's tier; {@code self} when owned, {@code shared} when
 * pooled</li>
 * <li>{@code pool} — bounded pool identifier, {@code none} unless set via {@link #withPool}</li>
 * </ul>
 *
 * <p>All five tags are always present so every relationship emits the same label set per observation
 * name (mixed label sets crash legacy Prometheus clients and silently split aggregations on current
 * ones). Values are static vocabulary — tiers and types,
 * never tenant UUIDs, user ids, or other unbounded identifiers. Factories throw on UUID-shaped or
 * long hexadecimal values at construction time, so misuse fails in tests, not on the pager.
 * Boundedness of pool ids is the meter filter's job; pair with
 * {@link MetricsMeterFilters#boundedTagValues}.
 *
 * <p>Usage: {@code observations.record(name, resource.tags().and(otherDimensions), work)}.
 *
 * @since 0.1.0
 */
public final class SharedResource {

    /** Resource type tag name. */
    public static final String TAG_RESOURCE = "resource";

    /** Relationship tag name. */
    public static final String TAG_RELATIONSHIP = "relationship";

    /** Consumer tier tag name. */
    public static final String TAG_CONSUMER_TIER = "consumer_tier";

    /** Owner tier tag name. */
    public static final String TAG_OWNER_TIER = "owner_tier";

    /** Pool tag name. */
    public static final String TAG_POOL = "pool";

    /** Owner tier value for owned resources. */
    public static final String OWNER_SELF = "self";

    /** Owner tier value for pooled resources. */
    public static final String OWNER_SHARED = "shared";

    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LONG_HEX = Pattern.compile("[0-9a-fA-F]{16,}");

    private final String resourceType;
    private final String relationship;
    private final String consumerTier;
    private final String ownerTier;
    private final String pool;

    private SharedResource(
            final String resourceType,
            final String relationship,
            final String consumerTier,
            final String ownerTier,
            final String pool) {
        this.resourceType = resourceType;
        this.relationship = relationship;
        this.consumerTier = consumerTier;
        this.ownerTier = ownerTier;
        this.pool = pool;
    }

    /**
     * Creates attribution tags for a resource the consumer owns.
     *
     * @param resourceType closed resource type; must not be blank or identifier-shaped
     * @param consumerTier consuming tenant's tier; must not be blank or identifier-shaped
     * @return shared-resource tags with {@code relationship=owned} and {@code owner_tier=self}
     */
    public static SharedResource owned(final String resourceType, final String consumerTier) {
        return new SharedResource(
                vocabulary("resourceType", resourceType),
                "owned",
                vocabulary("consumerTier", consumerTier),
                OWNER_SELF,
                MetricTagValues.NONE);
    }

    /**
     * Creates attribution tags for a resource borrowed from another tenant.
     *
     * @param resourceType closed resource type; must not be blank or identifier-shaped
     * @param consumerTier consuming tenant's tier; must not be blank or identifier-shaped
     * @param ownerTier    owning tenant's tier; must not be blank or identifier-shaped
     * @return shared-resource tags with {@code relationship=borrowed}
     */
    public static SharedResource borrowed(
            final String resourceType,
            final String consumerTier,
            final String ownerTier) {
        return new SharedResource(
                vocabulary("resourceType", resourceType),
                "borrowed",
                vocabulary("consumerTier", consumerTier),
                vocabulary("ownerTier", ownerTier),
                MetricTagValues.NONE);
    }

    /**
     * Creates attribution tags for a resource drawn from a shared pool.
     *
     * @param resourceType closed resource type; must not be blank or identifier-shaped
     * @param consumerTier consuming tenant's tier; must not be blank or identifier-shaped
     * @return shared-resource tags with {@code relationship=pooled} and {@code owner_tier=shared}
     */
    public static SharedResource pooled(final String resourceType, final String consumerTier) {
        return new SharedResource(
                vocabulary("resourceType", resourceType),
                "pooled",
                vocabulary("consumerTier", consumerTier),
                OWNER_SHARED,
                MetricTagValues.NONE);
    }

    /**
     * Returns a copy carrying a bounded pool identifier.
     *
     * @param poolId bounded pool identifier; must not be blank or identifier-shaped
     * @return a new instance with {@code pool=poolId}
     */
    public SharedResource withPool(final String poolId) {
        return new SharedResource(resourceType, relationship, consumerTier, ownerTier,
                vocabulary("poolId", poolId));
    }

    /**
     * Returns the five attribution tags.
     *
     * @return tags, never {@code null}
     */
    public KeyValues tags() {
        return KeyValues.of(
                MetricsTags.of(TAG_RESOURCE, resourceType),
                MetricsTags.of(TAG_RELATIONSHIP, relationship),
                MetricsTags.of(TAG_CONSUMER_TIER, consumerTier),
                MetricsTags.of(TAG_OWNER_TIER, ownerTier),
                MetricsTags.of(TAG_POOL, pool));
    }

    private static String vocabulary(final String parameter, final String value) {
        Objects.requireNonNull(value, () -> parameter + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(parameter + " must not be blank");
        }
        if (UUID_LIKE.matcher(value).find() || LONG_HEX.matcher(value).find()) {
            throw new IllegalArgumentException(
                    parameter + " looks like an identifier, not a closed vocabulary value: tiers and"
                            + " types belong in tags, tenant ids never do");
        }
        return MetricTagValues.sanitizeTagValue(value);
    }
}
