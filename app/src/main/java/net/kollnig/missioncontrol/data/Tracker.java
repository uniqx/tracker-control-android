/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 */

package net.kollnig.missioncontrol.data;

import androidx.annotation.NonNull;

public class Tracker {
    public String name;
    public String category;
    public Boolean necessary;

    public Tracker(String name, String category) {
        this.name = name;
        this.category = category;
        this.necessary = false;
    }

    public Tracker(String name, String category, Boolean necessary) {
        this.name = name;
        this.category = category;
        this.necessary = necessary;
    }

    @Override
    @NonNull
    public String toString() {
        if (TrackerList.necessaryTrackers.contains(name))
            return name + " (Unblocked)";
        else {
            return name;
        }
    }

    public String getCategory() {
        if (category == null || category.equals("null")) return null;
        return category;
    }

    public String getRoot() {
        if (getCategory() != null) return getCategory();
        return name;
    }
}
