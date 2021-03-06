// $Id$
/*
 * WorldGuard
 * Copyright (C) 2010 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.sk89q.worldguard.protection;

import com.sk89q.worldguard.protection.flags.*;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import com.sk89q.worldguard.LocalPlayer;

/**
 * Represents a setFlag of regions and their rules as applied to one point or
 * region.
 * 
 * @author sk89q
 */
public class ApplicableRegionSet {

    private Collection<ProtectedRegion> applicable;
    private ProtectedRegion globalRegion;

    /**
     * Construct the object.
     * 
     * @param applicable
     * @param globalRegion 
     */
    public ApplicableRegionSet(Collection<ProtectedRegion> applicable,
            ProtectedRegion globalRegion) {
        this.applicable = applicable;
        this.globalRegion = globalRegion;
    }
    
    /**
     * Checks if a player can build in an area.
     * 
     * @param player
     * @return
     */
    public boolean canBuild(LocalPlayer player) {
        return internalGetState(DefaultFlag.BUILD, player);
    }
    
    /**
     * Checks if a player can build in an area.
     * 
     * @param player
     * @return
     */
    public boolean canUse(LocalPlayer player) {
        return internalGetState(DefaultFlag.USE, player);
    }
    
    /**
     * Gets the state of a state flag. This cannot be used for the build flag.
     * 
     * @param flag
     * @return
     */
    public boolean allows(StateFlag flag) {
        return internalGetState(flag, null);
    }
    
    /**
     * Indicates whether a player is an owner of all regions in this set.
     * 
     * @param player
     * @return
     */
    public boolean isOwnerOfAll(LocalPlayer player) {
        for (ProtectedRegion region : applicable) {
            if (!region.isOwner(player)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Indicates whether a player is an owner or member of all regions in
     * this set.
     * 
     * @param player
     * @return
     */
    public boolean isMemberOfAll(LocalPlayer player) {
        for (ProtectedRegion region : applicable) {
            if (!region.isMember(player)) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Checks to see if a flag is permitted.
     * 
     * @param def
     *            default state if there are no regions defined
     * @param player
     *            null to not check owners and members
     * @return
     */
    private boolean internalGetState(StateFlag flag, LocalPlayer player) {
        boolean found = false;
        boolean allowed = false; // Used for ALLOW override
        boolean def = flag.getDefault();
        
        // Handle defaults
        if (globalRegion != null) {
            State globalState = globalRegion.getFlag(flag);
            
            // The global region has this flag set
            if (globalState != null) {
                def = (globalState == State.ALLOW);
            }
        }
        
        // The player argument is used if and only if the flag is the build
        // flag -- in which case, if there are any regions in this area, we
        // default to FALSE, otherwise true if there are no defined regions.
        // However, other flags are different -- if there are regions defined,
        // we default to the global region value. 
        if (player == null) {
            allowed = def; 
        }
        
        int lastPriority = 0;

        // The algorithm is as follows:
        // While iterating through the list of regions, if an entry disallows
        // the flag, then put it into the needsClear set. If an entry allows
        // the flag and it has a parent, then its parent is put into hasCleared.
        // In the situation that the child is reached before the parent, upon
        // the parent being reached, even if the parent disallows, because the
        // parent will be in hasCleared, permission will be allowed. In the
        // other case, where the parent is reached first, if it does not allow
        // permissions, it will be placed into needsClear. If a child of
        // the parent is reached later, the parent will be removed from
        // needsClear. At the end, if needsClear is not empty, that means that
        // permission should not be given. If a parent has multiple children
        // and one child does not allow permissions, then it will be placed into
        // needsClear just like as if was a parent.

        Set<ProtectedRegion> needsClear = new HashSet<ProtectedRegion>();
        Set<ProtectedRegion> hasCleared = new HashSet<ProtectedRegion>();

        Iterator<ProtectedRegion> it = applicable.iterator();
        
        while (it.hasNext()) {
            ProtectedRegion region = it.next();

            // Ignore lower priority regions
            if (found && region.getPriority() < lastPriority) {
                break;
            }

            // Ignore non-build regions
            if (player != null
                    && region.getFlag(DefaultFlag.PASSTHROUGH) == State.ALLOW) {
                continue;
            }
            
            State v = region.getFlag(flag);

            // Allow DENY to override everything
            if (v == State.DENY) {
                return false;
            }

            // Forget about regions that allow it, although make sure the
            // default state is now to allow
            if (v == State.ALLOW) {
                allowed = true;
                found = true;
                continue;
            }

            // For the build flag, the flags are conditional and are based
            // on membership, so we have to check for parent-child
            // relationships
            if (player != null) {
                if (hasCleared.contains(region)) {
                    // Already cleared, so do nothing
                } else {
                    if (!region.isMember(player)) {
                        needsClear.add(region);
                    } else {
                        // Need to clear all parents
                        clearParents(needsClear, hasCleared, region);
                    }
                }
            }

            found = true;
            lastPriority = region.getPriority();
        }

        return found == false ? def :
                (allowed || (player != null && needsClear.size() == 0));
    }

    /**
     * Clear a region's parents for isFlagAllowed().
     * 
     * @param needsClear
     * @param hasCleared
     * @param region
     */
    private void clearParents(Set<ProtectedRegion> needsClear,
            Set<ProtectedRegion> hasCleared, ProtectedRegion region) {
        ProtectedRegion parent = region.getParent();

        while (parent != null) {
            if (!needsClear.remove(parent)) {
                hasCleared.add(parent);
            }

            parent = parent.getParent();
        }
    }

    /**
     * Gets the value of a flag. Do not use this for state flags.
     * 
     * @param flag 
     * @param <T> 
     * @param <V> 
     * @return
     */
    public <T extends Flag<V>, V> V getFlag(T flag) {
        int lastPriority = 0;
        boolean found = false;

        Map<ProtectedRegion, V> needsClear = new HashMap<ProtectedRegion, V>();
        Set<ProtectedRegion> hasCleared = new HashSet<ProtectedRegion>();

        Iterator<ProtectedRegion> it = applicable.iterator();

        while (it.hasNext()) {
            ProtectedRegion region = it.next();

            // Ignore lower priority regions
            if (found && region.getPriority() < lastPriority) {
                break;
            }

            if (hasCleared.contains(region)) {
                // Already cleared, so do nothing
            } else if (region.getFlag(flag) != null){
                clearParents(needsClear, hasCleared, region);
                
                needsClear.put(region, region.getFlag(flag));
            }
            
            found = true;
            lastPriority = region.getPriority();
        }
        
        try {
            return needsClear.values().iterator().next();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    /**
     * Clear a region's parents for getFlag().
     * 
     * @param needsClear
     * @param hasCleared
     * @param region
     */
    private void clearParents(Map<ProtectedRegion, ?> needsClear,
            Set<ProtectedRegion> hasCleared, ProtectedRegion region) {
        ProtectedRegion parent = region.getParent();

        while (parent != null) {
            if (needsClear.remove(parent) == null) {
                hasCleared.add(parent);
            }

            parent = parent.getParent();
        }
    }
    
    /**
     * Get the number of regions that are included.
     * 
     * @return
     */
    public int size() {
        return applicable.size();
    }
    
    /**
     * Get an iterator of affected regions.
     * 
     * @return
     */
    public Iterator<ProtectedRegion> iterator() {
        return applicable.iterator();
    }
}
