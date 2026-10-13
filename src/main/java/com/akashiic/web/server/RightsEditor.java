package com.akashiic.web.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.RightsPolicy;
import com.akashiic.web.common.ScreenGeometry;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebSettings;
import com.akashiic.web.common.net.RightsChange;

/**
 * Applies a C2SRights request to a screen, all or nothing: every part is validated first (rights for each part, name
 * resolution, limits), and the screen changes only if all of it is acceptable. Pure; name lookup is injected.
 */
public final class RightsEditor {

    private RightsEditor() {}

    /** A resolved player: canonical name and UUID. */
    public static final class Profile {

        public final UUID id;
        public final String name;

        public Profile(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class Outcome {

        public final ReasonCode reason;
        /** First offending value for the notice (an unknown name, a resolution...). */
        public final String detail;
        public final boolean changed;
        /** Resolution or rotation changed (viewers must resize their page). */
        public final boolean geometryChanged;
        /** One line for the audit log. */
        public final String audit;

        Outcome(ReasonCode reason, String detail, boolean changed, boolean geometryChanged, String audit) {
            this.reason = reason;
            this.detail = detail;
            this.changed = changed;
            this.geometryChanged = geometryChanged;
            this.audit = audit;
        }

        static Outcome refuse(ReasonCode reason, String detail) {
            return new Outcome(reason, detail, false, false, "");
        }
    }

    public static Outcome apply(ScreenState s, RightsChange c, UUID actor, boolean admin, WebSettings cfg,
        Function<String, Profile> resolver) {
        int have = RightsPolicy.rights(s, actor, admin);
        boolean friendsChange = !c.addFriends.isEmpty() || !c.removeFriends.isEmpty();
        boolean rightsChange = c.friendRights != RightsChange.UNCHANGED || c.otherRights != RightsChange.UNCHANGED;
        boolean geometryChange = c.resolutionW != RightsChange.UNCHANGED || c.resolutionH != RightsChange.UNCHANGED
            || c.rotation != RightsChange.UNCHANGED;
        boolean lockChange = c.locked != RightsChange.UNCHANGED;

        if (lockChange && !admin) return Outcome.refuse(ReasonCode.NO_PERMISSION, "lock");
        ReasonCode need = need(s, actor, admin, friendsChange, Rights.MANAGE_FRIENDS);
        if (need != ReasonCode.OK) return Outcome.refuse(need, "friends");
        need = need(s, actor, admin, rightsChange, Rights.MANAGE_RIGHTS);
        if (need != ReasonCode.OK) return Outcome.refuse(need, "rights");
        need = need(s, actor, admin, geometryChange, Rights.CHANGE_RESOLUTION);
        if (need != ReasonCode.OK) return Outcome.refuse(need, "resolution");

        // Rights bits: only what the manager holds, and never management rights for visitors.
        if (c.friendRights != RightsChange.UNCHANGED
            && RightsPolicy.grantable(c.friendRights, have, false) != (c.friendRights & Rights.ALL)) {
            return Outcome.refuse(ReasonCode.NO_PERMISSION, "friendRights");
        }
        if (c.otherRights != RightsChange.UNCHANGED
            && RightsPolicy.grantable(c.otherRights, have, true) != (c.otherRights & Rights.ALL)) {
            return Outcome.refuse(ReasonCode.NO_PERMISSION, "otherRights");
        }

        // Friends: resolve every name before touching anything.
        Map<UUID, String> add = new LinkedHashMap<>();
        for (String name : c.addFriends) {
            Profile p = resolver.apply(name);
            if (p == null) return Outcome.refuse(ReasonCode.UNKNOWN_PLAYER, name);
            if (!p.id.equals(s.owner)) add.put(p.id, p.name);
        }
        List<UUID> remove = new ArrayList<>();
        for (String name : c.removeFriends) {
            UUID id = friendByName(s, name);
            if (id == null) {
                Profile p = resolver.apply(name);
                if (p == null) return Outcome.refuse(ReasonCode.UNKNOWN_PLAYER, name);
                id = p.id;
            }
            remove.add(id);
        }
        int friendsAfter = s.friends.size();
        for (UUID id : add.keySet()) if (!s.friends.contains(id) && !remove.contains(id)) friendsAfter++;
        for (UUID id : remove) if (s.friends.contains(id) && !add.containsKey(id)) friendsAfter--;
        if (friendsAfter > cfg.maxFriends)
            return Outcome.refuse(ReasonCode.TOO_MANY_FRIENDS, String.valueOf(cfg.maxFriends));

        // Geometry: a rotation between landscape and portrait swaps the page size unless a new size is given.
        int rotation = c.rotation == RightsChange.UNCHANGED ? s.rotation : c.rotation;
        int resW = s.resolutionW, resH = s.resolutionH;
        if (HitMath.swapsAxes(rotation) != HitMath.swapsAxes(s.rotation)) {
            resW = s.resolutionH;
            resH = s.resolutionW;
        }
        if (c.resolutionW != RightsChange.UNCHANGED) resW = c.resolutionW;
        if (c.resolutionH != RightsChange.UNCHANGED) resH = c.resolutionH;
        if (geometryChange && !ScreenGeometry
            .resolutionAllowed(resW, resH, cfg.maxResolutionW, cfg.maxResolutionH, cfg.maxResolutionPixels)) {
            return Outcome.refuse(ReasonCode.BAD_RESOLUTION, resW + "x" + resH);
        }

        // Everything is valid: apply.
        StringBuilder audit = new StringBuilder();
        boolean changed = false;
        for (Map.Entry<UUID, String> e : add.entrySet()) {
            if (s.friends.add(e.getKey())) {
                changed = true;
                audit.append(" +friend ")
                    .append(e.getValue());
            }
            s.friendNames.put(e.getKey(), e.getValue());
        }
        for (UUID id : remove) {
            if (add.containsKey(id)) continue;
            if (s.friends.remove(id)) {
                changed = true;
                audit.append(" -friend ")
                    .append(s.friendNames.get(id) == null ? id.toString() : s.friendNames.get(id));
            }
            s.friendNames.remove(id);
        }
        if (c.friendRights != RightsChange.UNCHANGED && s.friendRights != c.friendRights) {
            s.friendRights = c.friendRights & Rights.ALL;
            changed = true;
            audit.append(" friendRights=")
                .append(s.friendRights);
        }
        if (c.otherRights != RightsChange.UNCHANGED && s.otherRights != c.otherRights) {
            s.otherRights = c.otherRights & Rights.ALL;
            changed = true;
            audit.append(" otherRights=")
                .append(s.otherRights);
        }
        boolean geometryChanged = rotation != s.rotation || resW != s.resolutionW || resH != s.resolutionH;
        if (geometryChanged) {
            s.rotation = rotation;
            s.resolutionW = resW;
            s.resolutionH = resH;
            changed = true;
            audit.append(" page=")
                .append(resW)
                .append('x')
                .append(resH)
                .append(" rot=")
                .append(rotation);
        }
        if (lockChange && s.locked != (c.locked == 1)) {
            s.locked = c.locked == 1;
            changed = true;
            audit.append(s.locked ? " locked" : " unlocked");
        }
        return new Outcome(
            ReasonCode.OK,
            "",
            changed,
            geometryChanged,
            audit.toString()
                .trim());
    }

    private static ReasonCode need(ScreenState s, UUID actor, boolean admin, boolean wanted, int right) {
        return wanted ? RightsPolicy.denial(s, actor, admin, right) : ReasonCode.OK;
    }

    private static UUID friendByName(ScreenState s, String name) {
        for (Map.Entry<UUID, String> e : s.friendNames.entrySet()) {
            if (e.getValue() != null && e.getValue()
                .equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }
}
