// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud;

import android.os.Parcel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** The seven-field QisiNaviInfo parcel used by the measured HS5 Android 9 firmware. */
final class HongqiInstrumentGuidance {
    private static final int IMMEDIATE_METERS = 15;
    private static final int METERS_PER_KILOMETER = 1000;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int NO_ROUNDABOUT_EXIT = -1;
    private static final HongqiInstrumentGuidance EMPTY = new HongqiInstrumentGuidance(
            "", 0, "", "", "", 0, NO_ROUNDABOUT_EXIT);

    private final String nextDistance;
    private final int distanceRange;
    private final String nextRoad;
    private final String remainingDistance;
    private final String remainingTime;
    private final int direction;
    private final int roundaboutExit;

    private HongqiInstrumentGuidance(String nextDistance, int distanceRange, String nextRoad,
            String remainingDistance, String remainingTime, int direction, int roundaboutExit) {
        this.nextDistance = nextDistance;
        this.distanceRange = distanceRange;
        this.nextRoad = nextRoad;
        this.remainingDistance = remainingDistance;
        this.remainingTime = remainingTime;
        this.direction = direction;
        this.roundaboutExit = roundaboutExit;
    }

    static HongqiInstrumentGuidance from(ClusterTurnGuidance guidance) {
        if (guidance == null) return EMPTY;
        int meters = Math.max(0, guidance.getDistanceMeters());
        int icon = guidance.getIcon();
        int exit = guidance.getRoundaboutExit();
        // Stock NaviDataManager subtracts one from the map's numbered roundabout exit.
        int nativeExit = exit >= 1 && exit <= 16 ? exit - 1 : NO_ROUNDABOUT_EXIT;
        return new HongqiInstrumentGuidance(
                meters < IMMEDIATE_METERS ? "现在" : distanceLabel((long) meters),
                HongqiNavigation.nativeDistance(meters)[0],
                guidance.getRoad(),
                distanceLabel(guidance.getRemainingMeters()),
                durationLabel(guidance.getRemainingSeconds()),
                icon >= 0 && icon <= 31 ? icon : 0,
                nativeExit);
    }

    private static String distanceLabel(Long meters) {
        if (meters == null || meters < 0 || meters > Integer.MAX_VALUE) return "";
        if (meters < METERS_PER_KILOMETER) return meters + "米";
        return BigDecimal.valueOf(meters).divide(BigDecimal.valueOf(METERS_PER_KILOMETER),
                1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "公里";
    }

    private static String durationLabel(Long seconds) {
        if (seconds == null || seconds < 0 || seconds > Integer.MAX_VALUE) return "";
        // Stock capture: 1906 seconds is shown as 31分, not a rounded-up 32分.
        long minutes = seconds == 0 ? 0 : Math.max(1, seconds / SECONDS_PER_MINUTE);
        if (minutes < MINUTES_PER_HOUR) return minutes + "分";
        long hours = minutes / MINUTES_PER_HOUR;
        long remainder = minutes % MINUTES_PER_HOUR;
        return hours + "小时" + (remainder == 0 ? "" : remainder + "分");
    }

    void writeTo(Parcel parcel) {
        // QisiNaviInfo.writeToParcel order, not the order of its JSON keys or Java fields.
        parcel.writeString(nextDistance);
        parcel.writeInt(distanceRange);
        parcel.writeString(nextRoad);
        parcel.writeString(remainingDistance);
        parcel.writeString(remainingTime);
        parcel.writeInt(direction);
        parcel.writeInt(roundaboutExit);
    }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HongqiInstrumentGuidance)) return false;
        HongqiInstrumentGuidance other = (HongqiInstrumentGuidance) value;
        return distanceRange == other.distanceRange && direction == other.direction
                && roundaboutExit == other.roundaboutExit && nextDistance.equals(other.nextDistance)
                && nextRoad.equals(other.nextRoad) && remainingDistance.equals(other.remainingDistance)
                && remainingTime.equals(other.remainingTime);
    }

    @Override public int hashCode() {
        return Objects.hash(nextDistance, distanceRange, nextRoad, remainingDistance,
                remainingTime, direction, roundaboutExit);
    }
}
