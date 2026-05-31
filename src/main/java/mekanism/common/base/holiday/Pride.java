package mekanism.common.base.holiday;

import java.time.Month;
import net.minecraft.world.entity.player.Player;

class Pride extends Holiday {

    public static final Pride INSTANCE = new Pride();

    private Pride() {
        super(new MonthlyDate(Month.JUNE));
    }

    @Override
    HolidayMessage getMessage(Player player) {
        return null;
    }
}