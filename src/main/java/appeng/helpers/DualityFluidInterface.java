/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2018, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.helpers;

import java.util.Optional;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.Upgrades;
import appeng.api.implementations.IUpgradeableHost;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.IStorageMonitorableAccessor;
import appeng.api.storage.StorageChannels;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalBlockPos;
import appeng.api.util.IConfigManager;
import appeng.capabilities.Capabilities;
import appeng.core.settings.TickRates;
import appeng.me.helpers.MachineSource;
import appeng.me.storage.MEMonitorIFluidHandler;
import appeng.me.storage.MEMonitorPassThrough;
import appeng.me.storage.NullInventory;
import appeng.util.ConfigManager;
import appeng.util.IConfigManagerHost;
import appeng.util.Platform;
import appeng.util.fluid.AEFluidInventory;
import appeng.util.fluid.IAEFluidTank;
import appeng.util.inv.IAEFluidInventory;

public class DualityFluidInterface
        implements IGridTickable, IStorageMonitorable, IAEFluidInventory, IUpgradeableHost, IConfigManagerHost,
        IConfigurableFluidInventory {
    public static final int NUMBER_OF_TANKS = 6;
    public static final int TANK_CAPACITY = FluidAttributes.BUCKET_VOLUME * 4;

    private final ConfigManager cm = new ConfigManager(this);
    private final IManagedGridNode gridProxy;
    private final IFluidInterfaceHost iHost;
    private final IActionSource mySource;
    private final IActionSource interfaceRequestSource;
    private boolean hasConfig = false;
    private final IStorageMonitorableAccessor accessor = this::getMonitorable;
    private final AEFluidInventory tanks = new AEFluidInventory(this, NUMBER_OF_TANKS, TANK_CAPACITY);
    private final AEFluidInventory config = new AEFluidInventory(this, NUMBER_OF_TANKS);
    private final IAEFluidStack[] requireWork;
    private int isWorking = -1;
    private int priority;

    private final MEMonitorPassThrough<IAEItemStack> items = new MEMonitorPassThrough<>(
            new NullInventory<IAEItemStack>(), StorageChannels.items());
    private final MEMonitorPassThrough<IAEFluidStack> fluids = new MEMonitorPassThrough<>(
            new NullInventory<IAEFluidStack>(), StorageChannels.fluids());

    public DualityFluidInterface(final IManagedGridNode mainNode, final IFluidInterfaceHost ih) {
        this.gridProxy = mainNode
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(IGridTickable.class, this);
        this.iHost = ih;

        this.mySource = new MachineSource(this.iHost);
        this.interfaceRequestSource = new InterfaceRequestSource(this.iHost);

        this.fluids.setChangeSource(this.mySource);
        this.items.setChangeSource(this.mySource);

        this.requireWork = new IAEFluidStack[NUMBER_OF_TANKS];
        for (int i = 0; i < NUMBER_OF_TANKS; ++i) {
            this.requireWork[i] = null;
        }
    }

    public IUpgradeableHost getHost() {
        if (this.iHost instanceof IUpgradeableHost) {
            return this.iHost;
        }
        if (this.iHost instanceof IUpgradeableHost) {
            return this.iHost;
        }
        return null;
    }

    @Override
    public <T extends IAEStack<T>> IMEMonitor<T> getInventory(IStorageChannel<T> channel) {
        if (channel == StorageChannels.items()) {
            if (this.hasConfig()) {
                return null;
            }

            return (IMEMonitor<T>) this.items;
        } else if (channel == StorageChannels.fluids()) {
            if (this.hasConfig()) {
                return (IMEMonitor<T>) new InterfaceInventory(this);
            }

            return (IMEMonitor<T>) this.fluids;
        }

        return null;
    }

    public IStorageMonitorable getMonitorable(final IActionSource src) {
        if (Platform.canAccess(this.gridProxy, src)) {
            return this;
        }

        return null;
    }

    @Override
    public TickingRequest getTickingRequest(final IGridNode node) {
        return new TickingRequest(TickRates.Interface.getMin(), TickRates.Interface.getMax(), !this.hasWorkToDo(),
                true);
    }

    @Override
    public TickRateModulation tickingRequest(final IGridNode node, final int ticksSinceLastCall) {
        if (!this.gridProxy.isActive()) {
            return TickRateModulation.SLEEP;
        }

        final boolean couldDoWork = this.updateStorage();
        return this.hasWorkToDo() ? couldDoWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER
                : TickRateModulation.SLEEP;
    }

    public void notifyNeighbors() {
        if (this.gridProxy.isActive()) {
            this.gridProxy.ifPresent((grid, node) -> {
                grid.getTickManager().wakeDevice(node);
            });
        }

        final BlockEntity te = this.iHost.getBlockEntity();
        if (te != null && te.getLevel() != null) {
            Platform.notifyBlocksOfNeighbors(te.getLevel(), te.getBlockPos());
        }
    }

    public void gridChanged() {
        var grid = gridProxy.getGrid();
        if (grid != null) {
            this.items.setInternal(grid.getStorageService()
                    .getInventory(StorageChannels.items()));
            this.fluids.setInternal(grid.getStorageService()
                    .getInventory(StorageChannels.fluids()));
        } else {
            this.items.setInternal(new NullInventory<>());
            this.fluids.setInternal(new NullInventory<>());
        }

        this.notifyNeighbors();
    }

    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    public DimensionalBlockPos getLocation() {
        return new DimensionalBlockPos(this.iHost.getBlockEntity());
    }

    @SuppressWarnings("unchecked")
    public <T> LazyOptional<T> getCapability(Capability<T> capabilityClass, Direction facing) {
        if (capabilityClass == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return (LazyOptional<T>) LazyOptional.of(() -> this.tanks);
        } else if (capabilityClass == Capabilities.STORAGE_MONITORABLE_ACCESSOR) {
            return (LazyOptional<T>) LazyOptional.of(() -> this.accessor);
        }
        return LazyOptional.empty();
    }

    private boolean hasConfig() {
        return this.hasConfig;
    }

    private void readConfig() {
        this.hasConfig = false;

        for (int i = 0; i < this.config.getSlots(); i++) {
            if (this.config.getFluidInSlot(i) != null) {
                this.hasConfig = true;
                break;
            }
        }

        final boolean had = this.hasWorkToDo();

        for (int x = 0; x < NUMBER_OF_TANKS; x++) {
            this.updatePlan(x);
        }

        final boolean has = this.hasWorkToDo();

        if (had != has) {
            gridProxy.ifPresent((grid, node) -> {
                if (has) {
                    grid.getTickManager().alertDevice(node);
                } else {
                    grid.getTickManager().sleepDevice(node);
                }
            });
        }

        this.notifyNeighbors();
    }

    private boolean updateStorage() {
        boolean didSomething = false;
        for (int x = 0; x < NUMBER_OF_TANKS; x++) {
            if (this.requireWork[x] != null) {
                didSomething = this.usePlan(x) || didSomething;
            }
        }
        return didSomething;
    }

    private boolean hasWorkToDo() {
        for (final IAEFluidStack requiredWork : this.requireWork) {
            if (requiredWork != null) {
                return true;
            }
        }

        return false;
    }

    private void updatePlan(final int slot) {
        final IAEFluidStack req = this.config.getFluidInSlot(slot);
        final IAEFluidStack stored = this.tanks.getFluidInSlot(slot);

        if (req == null && stored != null && stored.getStackSize() > 0) {
            final IAEFluidStack work = stored.copy();
            this.requireWork[slot] = work.setStackSize(-work.getStackSize());
            return;
        } else if (req != null) {
            if (stored == null || stored.getStackSize() == 0) // need to add stuff!
            {
                this.requireWork[slot] = req.copy();
                this.requireWork[slot].setStackSize(TANK_CAPACITY);
                return;
            } else if (req.equals(stored)) // same type ( qty different? )!
            {
                if (stored.getStackSize() < TANK_CAPACITY) {
                    this.requireWork[slot] = req.copy();
                    this.requireWork[slot].setStackSize(TANK_CAPACITY - stored.getStackSize());
                    return;
                }
            } else
            // Stored != null; dispose!
            {
                final IAEFluidStack work = stored.copy();
                this.requireWork[slot] = work.setStackSize(-work.getStackSize());
                return;
            }
        }

        this.requireWork[slot] = null;
    }

    private boolean usePlan(final int slot) {
        IAEFluidStack work = this.requireWork[slot];
        this.isWorking = slot;

        boolean changed = false;
        var grid = this.gridProxy.getGrid();
        if (grid != null) {
            final IMEInventory<IAEFluidStack> dest = grid.getStorageService()
                    .getInventory(StorageChannels.fluids());
            final IEnergySource src = grid.getEnergyService();

            if (work.getStackSize() > 0) {
                // make sure strange things didn't happen...
                if (this.tanks.fill(slot, work.getFluidStack(), false) != work.getStackSize()) {
                    changed = true;
                } else {
                    final IAEFluidStack acquired = Platform.poweredExtraction(src, dest, work,
                            this.interfaceRequestSource);
                    if (acquired != null) {
                        changed = true;
                        final int filled = this.tanks.fill(slot, acquired.getFluidStack(), true);
                        if (filled != acquired.getStackSize()) {
                            throw new IllegalStateException("bad attempt at managing tanks. ( fill )");
                        }
                    }
                }
            } else if (work.getStackSize() < 0) {
                IAEFluidStack toStore = work.copy();
                toStore.setStackSize(-toStore.getStackSize());

                // make sure strange things didn't happen...
                final FluidStack canExtract = this.tanks.drain(slot, toStore.getFluidStack(), false);
                if (canExtract.isEmpty() || canExtract.getAmount() != toStore.getStackSize()) {
                    changed = true;
                } else {
                    IAEFluidStack notStored = Platform.poweredInsert(src, dest, toStore, this.interfaceRequestSource);
                    toStore.setStackSize(toStore.getStackSize() - (notStored == null ? 0 : notStored.getStackSize()));

                    if (toStore.getStackSize() > 0) {
                        // extract items!
                        changed = true;
                        final FluidStack removed = this.tanks.drain(slot, toStore.getFluidStack(), true);
                        if (removed.isEmpty() || toStore.getStackSize() != removed.getAmount()) {
                            throw new IllegalStateException("bad attempt at managing tanks. ( drain )");
                        }
                    }
                }
            }
        }

        if (changed) {
            this.updatePlan(slot);
        }

        this.isWorking = -1;
        return changed;
    }

    @Override
    public void onFluidInventoryChanged(final IAEFluidTank inventory, final int slot) {
        if (this.isWorking == slot) {
            return;
        }

        if (inventory == this.config) {
            this.readConfig();
        } else if (inventory == this.tanks) {
            this.saveChanges();

            final boolean had = this.hasWorkToDo();

            this.updatePlan(slot);

            final boolean now = this.hasWorkToDo();

            if (had != now) {
                gridProxy.ifPresent((grid, node) -> {
                    if (now) {
                        grid.getTickManager().alertDevice(node);
                    } else {
                        grid.getTickManager().sleepDevice(node);
                    }
                });
            }
        }
    }

    public int getPriority() {
        return this.priority;
    }

    public void setPriority(final int newValue) {
        this.priority = newValue;
    }

    @Override
    public boolean isRemote() {
        Level level = this.iHost.getBlockEntity().getLevel();
        return level == null || level.isClientSide();
    }

    public void writeToNBT(final CompoundTag data) {
        data.putInt("priority", this.priority);
        this.tanks.writeToNBT(data, "storage");
        this.config.writeToNBT(data, "config");
    }

    public void readFromNBT(final CompoundTag data) {
        this.config.readFromNBT(data, "config");
        this.tanks.readFromNBT(data, "storage");
        this.priority = data.getInt("priority");
        this.readConfig();
    }

    public IAEFluidTank getConfig() {
        return this.config;
    }

    public IAEFluidTank getTanks() {
        return this.tanks;
    }

    private class InterfaceRequestSource extends MachineSource {
        private final InterfaceRequestContext context;

        InterfaceRequestSource(IActionHost v) {
            super(v);
            this.context = new InterfaceRequestContext();
        }

        @Override
        public <T> Optional<T> context(Class<T> key) {
            if (key == InterfaceRequestContext.class) {
                return (Optional<T>) Optional.of(this.context);
            }

            return super.context(key);
        }
    }

    private class InterfaceRequestContext implements Comparable<Integer> {
        @Override
        public int compareTo(Integer o) {
            return Integer.compare(DualityFluidInterface.this.priority, o);
        }
    }

    private class InterfaceInventory extends MEMonitorIFluidHandler {

        InterfaceInventory(final DualityFluidInterface iface) {
            super(iface.tanks);
            this.setActionSource(new MachineSource(iface.iHost));
        }

        @Override
        public IAEFluidStack injectItems(final IAEFluidStack input, final Actionable type, final IActionSource src) {
            final Optional<InterfaceRequestContext> context = src.context(InterfaceRequestContext.class);
            final boolean isInterface = context.isPresent();

            if (isInterface) {
                return input;
            }

            return super.injectItems(input, type, src);
        }

        @Override
        public IAEFluidStack extractItems(final IAEFluidStack request, final Actionable type, final IActionSource src) {
            final Optional<InterfaceRequestContext> context = src.context(InterfaceRequestContext.class);
            final boolean hasLowerOrEqualPriority = context
                    .map(c -> c.compareTo(DualityFluidInterface.this.priority) <= 0).orElse(false);

            if (hasLowerOrEqualPriority) {
                return null;
            }

            return super.extractItems(request, type, src);
        }
    }

    public void saveChanges() {
        this.iHost.saveChanges();
    }

    @Override
    public IConfigManager getConfigManager() {
        return this.cm;
    }

    @Override
    public IItemHandler getInventoryByName(String name) {
        return null;
    }

    @Override
    public IFluidHandler getFluidInventoryByName(final String name) {
        if (name.equals("config")) {
            return this.config;
        }
        return null;
    }

    @Override
    public int getInstalledUpgrades(Upgrades u) {
        return 0;
    }

    @Override
    public BlockEntity getBlockEntity() {
        return (BlockEntity) (this.iHost instanceof BlockEntity ? this.iHost : null);
    }

    @Override
    public void updateSetting(IConfigManager manager, Settings settingName, Enum<?> newValue) {
    }
}