package com.paulfinlay.simassigner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * SIM Assigner: sets the per-number "preferred phone account" (the SIM the dialler uses)
 * on contacts, either one at a time (test) or in bulk by contact label/group.
 */
public class MainActivity extends Activity {

    // ContactsContract.DataColumns, API 28+. Literal names so nothing depends on SDK constants.
    static final String COL_COMP = "preferred_phone_account_component_name";
    static final String COL_ID = "preferred_phone_account_id";

    static final int REQ_PERMS = 42;
    static final String[] PERMS = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
    };

    static class SimInfo {
        String label;
        String component;
        String id;
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        int slot = -1;
        String carrier;

        String display() {
            String base = slot >= 0 ? "SIM " + (slot + 1) + " – " + label : label;
            if (carrier != null && !carrier.equals(label)) base += " (" + carrier + ")";
            return base;
        }
    }

    static class GroupRule {
        long groupId;
        String title;
        Spinner spinner;
        int choice; // 0 = no change, 1 = clear, 2+ = sims.get(choice - 2)
    }

    static class Change {
        long dataId;
        String name;
        String number;
        String oldComp, oldId, newComp, newId;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private final List<SimInfo> sims = new ArrayList<>();
    private Map<String, String> snapshot;
    private String snapshotName;
    private final List<GroupRule> rules = new ArrayList<>();
    private List<Change> pending;
    private CheckBox onlyEmpty;
    private TextView bulkOutput;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(tab("SIMs", v -> showSims()));
        tabs.addView(tab("Inspect / Test", v -> showInspect()));
        tabs.addView(tab("Bulk by label", v -> showBulk()));
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        content.setPadding(p, p, p, p);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets s = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return WindowInsets.CONSUMED;
        });

        setContentView(root);

        if (hasPerms()) showSims();
        else requestPermissions(PERMS, REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (hasPerms()) showSims();
        else showNoPerms();
    }

    private boolean hasPerms() {
        for (String p : PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean guard() {
        if (hasPerms()) return true;
        showNoPerms();
        return false;
    }

    private void showNoPerms() {
        content.removeAllViews();
        heading("Permissions needed");
        text("SIM Assigner needs Contacts (read and write) and Phone (to list your SIMs). "
                + "Nothing leaves the phone.");
        button("Grant permissions", v -> requestPermissions(PERMS, REQ_PERMS));
    }

    // ------------------------------------------------------------------ SIMs tab

    private void showSims() {
        if (!guard()) return;
        content.removeAllViews();
        heading("SIMs / calling accounts");
        loadSims();
        if (sims.isEmpty()) {
            text("No call-capable accounts found.");
            return;
        }
        for (SimInfo s : sims) {
            mono(content, s.display()
                    + "\n  slot:      " + (s.slot >= 0 ? (s.slot + 1) : "?")
                    + "\n  subId:     " + s.subId
                    + "\n  component: " + s.component
                    + "\n  id:        " + s.id);
        }
        text("These are the values written into each contact number's preferred-SIM fields.");
    }

    private void loadSims() {
        sims.clear();
        try {
            TelecomManager tm = getSystemService(TelecomManager.class);
            TelephonyManager tel = getSystemService(TelephonyManager.class);
            SubscriptionManager sm = getSystemService(SubscriptionManager.class);
            for (PhoneAccountHandle h : tm.getCallCapablePhoneAccounts()) {
                SimInfo s = new SimInfo();
                s.component = h.getComponentName().flattenToString();
                s.id = h.getId();
                PhoneAccount pa = tm.getPhoneAccount(h);
                s.label = (pa != null && pa.getLabel() != null) ? pa.getLabel().toString() : s.id;
                try {
                    s.subId = tel.getSubscriptionId(h);
                } catch (Exception ignored) {
                }
                if (s.subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    SubscriptionInfo si = sm.getActiveSubscriptionInfo(s.subId);
                    if (si != null) {
                        s.slot = si.getSimSlotIndex();
                        s.carrier = String.valueOf(si.getDisplayName());
                    }
                }
                sims.add(s);
            }
            sims.sort((a, b) -> Integer.compare(a.slot, b.slot));
        } catch (SecurityException e) {
            toast("Phone permission missing: " + e.getMessage());
        }
    }

    private String simLabelFor(String comp, String id) {
        if (id == null) return "none";
        for (SimInfo s : sims) {
            if (id.equals(s.id) && (comp == null || comp.equals(s.component))) return s.display();
        }
        return "other (" + id + ")";
    }

    // ------------------------------------------------------------------ Inspect / Test tab

    private void showInspect() {
        if (!guard()) return;
        content.removeAllViews();
        loadSims();
        heading("Inspect / test one contact");
        text("Search for a contact. You can test-set a SIM on it, or use Snapshot → change "
                + "Preferred SIM in Samsung Contacts → Compare, to see exactly where Samsung stores it.");
        EditText q = new EditText(this);
        q.setHint("Contact name (blank = first 50)");
        q.setSingleLine(true);
        content.addView(q);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        button("Search", v -> search(q.getText().toString().trim(), results));
        content.addView(results);
        if (snapshot != null) text("Snapshot held for: " + snapshotName);
    }

    private void search(String query, LinearLayout results) {
        results.removeAllViews();
        int n = 0;
        try (Cursor c = getContentResolver().query(Contacts.CONTENT_URI,
                new String[]{Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY},
                Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?", new String[]{"%" + query + "%"},
                Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE")) {
            while (c != null && c.moveToNext() && n < 50) {
                long id = c.getLong(0);
                String name = c.getString(1) == null ? "(no name)" : c.getString(1);
                TextView t = text(results, "›  " + name);
                t.setPadding(0, dp(10), 0, dp(10));
                t.setOnClickListener(v -> showContact(id, name));
                n++;
            }
        } catch (Exception e) {
            text(results, "Search failed: " + e);
        }
        if (n == 0) text(results, "No matches.");
    }

    private void showContact(long contactId, String name) {
        content.removeAllViews();
        loadSims();
        heading(name);

        // Current state of each number
        try (Cursor c = getContentResolver().query(Data.CONTENT_URI,
                new String[]{Data._ID, Phone.NUMBER, COL_COMP, COL_ID},
                Data.CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                new String[]{String.valueOf(contactId), Phone.CONTENT_ITEM_TYPE}, null)) {
            int n = 0;
            while (c != null && c.moveToNext()) {
                text(c.getString(1) + "  →  " + simLabelFor(c.getString(2), c.getString(3)));
                n++;
            }
            if (n == 0) text("This contact has no phone numbers.");
        } catch (Exception e) {
            text("Couldn't read numbers: " + e);
        }

        heading("Test write");
        for (SimInfo s : sims) {
            button("Set " + s.display(), v -> {
                setSim(contactId, s);
                showContact(contactId, name);
            });
        }
        button("Clear SIM preference", v -> {
            setSim(contactId, null);
            showContact(contactId, name);
        });

        heading("Find Samsung's storage");
        TextView out = new TextView(this);
        button("1. Take snapshot", v -> {
            snapshot = dumpContact(contactId);
            snapshotName = name;
            toast("Snapshot taken. Now change Preferred SIM for " + name
                    + " in Samsung Contacts, come back and tap Compare.");
        });
        button("2. Compare with snapshot", v -> {
            if (snapshot == null) {
                toast("Take a snapshot first.");
                return;
            }
            out.setText(diff(snapshot, dumpContact(contactId)));
        });
        button("Copy result / dump to clipboard", v -> {
            String s = out.getText().length() > 0 ? out.getText().toString()
                    : formatDump(dumpContact(contactId));
            getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("SIM Assigner", s));
            toast("Copied.");
        });
        button("‹ Back to search", v -> showInspect());

        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextIsSelectable(true);
        out.setPadding(0, dp(12), 0, 0);
        content.addView(out);
    }

    private void setSim(long contactId, SimInfo s) {
        ContentValues v = new ContentValues();
        if (s == null) {
            v.putNull(COL_COMP);
            v.putNull(COL_ID);
        } else {
            v.put(COL_COMP, s.component);
            v.put(COL_ID, s.id);
        }
        try {
            int n = getContentResolver().update(Data.CONTENT_URI, v,
                    Data.CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                    new String[]{String.valueOf(contactId), Phone.CONTENT_ITEM_TYPE});
            toast("Updated " + n + " number(s).");
        } catch (Exception e) {
            alert("Write failed", e.toString());
        }
    }

    /** Every non-null, non-blob column of every raw contact and data row for the contact. */
    private Map<String, String> dumpContact(long contactId) {
        Map<String, String> m = new LinkedHashMap<>();
        ContentResolver cr = getContentResolver();
        String[] args = {String.valueOf(contactId)};
        try (Cursor c = cr.query(RawContacts.CONTENT_URI, null,
                RawContacts.CONTACT_ID + "=?", args, RawContacts._ID)) {
            while (c != null && c.moveToNext()) {
                putRow(m, c, "raw#" + c.getLong(c.getColumnIndexOrThrow(RawContacts._ID)));
            }
        } catch (Exception e) {
            m.put("error.raw", e.toString());
        }
        try (Cursor c = cr.query(Data.CONTENT_URI, null,
                Data.CONTACT_ID + "=?", args, Data._ID)) {
            while (c != null && c.moveToNext()) {
                String mime = c.getString(c.getColumnIndexOrThrow(Data.MIMETYPE));
                String shortMime = mime == null ? "?" : mime.substring(mime.lastIndexOf('/') + 1);
                putRow(m, c, "data#" + c.getLong(c.getColumnIndexOrThrow(Data._ID)) + "(" + shortMime + ")");
            }
        } catch (Exception e) {
            m.put("error.data", e.toString());
        }
        return m;
    }

    private static void putRow(Map<String, String> m, Cursor c, String prefix) {
        for (int i = 0; i < c.getColumnCount(); i++) {
            int type = c.getType(i);
            if (type == Cursor.FIELD_TYPE_NULL || type == Cursor.FIELD_TYPE_BLOB) continue;
            m.put(prefix + "." + c.getColumnName(i), c.getString(i));
        }
    }

    private static String diff(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            String a = before.get(k), b = after.get(k);
            if (!Objects.equals(a, b)) {
                sb.append(k).append("\n   before: ").append(a)
                        .append("\n   after:  ").append(b).append("\n\n");
            }
        }
        if (sb.length() == 0) {
            return "No differences in the contacts database.\n\nIf you did change Preferred SIM in "
                    + "Samsung Contacts, Samsung keeps it in its own private store, not in the contact.";
        }
        return "Differences:\n\n" + sb;
    }

    private static String formatDump(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Bulk tab

    private void showBulk() {
        if (!guard()) return;
        content.removeAllViews();
        loadSims();
        rules.clear();
        pending = null;

        heading("Bulk assign by label");
        text("Pick a SIM for each label. A contact in two labels set to different SIMs is skipped "
                + "and listed, so you can sort it out. Tap Preview before Apply.");

        List<String> options = new ArrayList<>(Arrays.asList("No change", "Clear preference"));
        for (SimInfo s : sims) options.add(s.display());

        try (Cursor c = getContentResolver().query(Groups.CONTENT_SUMMARY_URI,
                new String[]{Groups._ID, Groups.TITLE, Groups.ACCOUNT_NAME, Groups.SUMMARY_COUNT},
                Groups.DELETED + "=0", null, Groups.TITLE + " COLLATE NOCASE")) {
            while (c != null && c.moveToNext()) {
                GroupRule r = new GroupRule();
                r.groupId = c.getLong(0);
                r.title = c.getString(1) == null ? "(untitled)" : c.getString(1);
                String account = c.getString(2) == null ? "phone" : c.getString(2);
                int count = c.getInt(3);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(6), 0, dp(6));
                TextView t = new TextView(this);
                t.setText(r.title + "\n" + count + " contacts · " + account);
                t.setTextSize(15);
                row.addView(t, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r.spinner = new Spinner(this);
                ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, options);
                ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                r.spinner.setAdapter(ad);
                row.addView(r.spinner);
                content.addView(row);
                rules.add(r);
            }
        } catch (Exception e) {
            text("Couldn't read labels: " + e);
        }
        if (rules.isEmpty()) text("No labels found.");

        onlyEmpty = new CheckBox(this);
        onlyEmpty.setText("Only fill numbers that have no SIM set yet");
        content.addView(onlyEmpty);

        button("Preview", v -> preview());
        button("Apply", v -> confirmApply());
        button("Restore last backup", v -> confirmRestore());

        bulkOutput = new TextView(this);
        bulkOutput.setTypeface(Typeface.MONOSPACE);
        bulkOutput.setTextSize(12);
        bulkOutput.setTextIsSelectable(true);
        bulkOutput.setPadding(0, dp(12), 0, 0);
        content.addView(bulkOutput);
    }

    private void preview() {
        List<GroupRule> active = new ArrayList<>();
        for (GroupRule r : rules) {
            r.choice = r.spinner.getSelectedItemPosition();
            if (r.choice > 0) active.add(r);
        }
        if (active.isEmpty()) {
            toast("Pick a SIM for at least one label.");
            return;
        }
        boolean only = onlyEmpty.isChecked();
        List<SimInfo> simsCopy = new ArrayList<>(sims);
        bulkOutput.setText("Working…");
        pending = null;
        new Thread(() -> {
            try {
                List<Change> changes = new ArrayList<>();
                String report = computePreview(active, simsCopy, only, changes);
                ui.post(() -> {
                    pending = changes;
                    bulkOutput.setText(report);
                });
            } catch (Exception e) {
                ui.post(() -> bulkOutput.setText("Preview failed: " + e));
            }
        }).start();
    }

    private String computePreview(List<GroupRule> active, List<SimInfo> simList,
                                  boolean onlyEmptyNumbers, List<Change> out) {
        ContentResolver cr = getContentResolver();
        Map<Long, Integer> target = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        Set<Long> conflicts = new HashSet<>();

        for (GroupRule r : active) {
            try (Cursor c = cr.query(Data.CONTENT_URI,
                    new String[]{Data.CONTACT_ID, Data.DISPLAY_NAME_PRIMARY},
                    Data.MIMETYPE + "=? AND " + GroupMembership.GROUP_ROW_ID + "=?",
                    new String[]{GroupMembership.CONTENT_ITEM_TYPE, String.valueOf(r.groupId)},
                    null)) {
                while (c != null && c.moveToNext()) {
                    long cid = c.getLong(0);
                    names.put(cid, c.getString(1));
                    Integer prev = target.get(cid);
                    if (prev == null) target.put(cid, r.choice);
                    else if (prev != r.choice) conflicts.add(cid);
                }
            }
        }
        for (Long cid : conflicts) target.remove(cid);

        int unchanged = 0, skippedSet = 0;
        List<Long> ids = new ArrayList<>(target.keySet());
        for (int start = 0; start < ids.size(); start += 400) {
            List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + 400));
            StringBuilder in = new StringBuilder();
            String[] args = new String[chunk.size() + 1];
            args[0] = Phone.CONTENT_ITEM_TYPE;
            for (int i = 0; i < chunk.size(); i++) {
                in.append(i == 0 ? "?" : ",?");
                args[i + 1] = String.valueOf(chunk.get(i));
            }
            try (Cursor c = cr.query(Data.CONTENT_URI,
                    new String[]{Data._ID, Data.CONTACT_ID, Data.DISPLAY_NAME_PRIMARY,
                            Phone.NUMBER, COL_COMP, COL_ID},
                    Data.MIMETYPE + "=? AND " + Data.CONTACT_ID + " IN (" + in + ")",
                    args, Data.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE")) {
                while (c != null && c.moveToNext()) {
                    Change ch = new Change();
                    ch.dataId = c.getLong(0);
                    long cid = c.getLong(1);
                    ch.name = c.getString(2);
                    ch.number = c.getString(3);
                    ch.oldComp = c.getString(4);
                    ch.oldId = c.getString(5);
                    int choice = target.get(cid);
                    if (choice >= 2) {
                        SimInfo s = simList.get(choice - 2);
                        ch.newComp = s.component;
                        ch.newId = s.id;
                    }
                    if (Objects.equals(ch.oldComp, ch.newComp) && Objects.equals(ch.oldId, ch.newId)) {
                        unchanged++;
                        continue;
                    }
                    if (onlyEmptyNumbers && ch.oldId != null) {
                        skippedSet++;
                        continue;
                    }
                    out.add(ch);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Labels used:        ").append(active.size()).append('\n');
        sb.append("Contacts matched:   ").append(target.size()).append('\n');
        sb.append("Numbers to change:  ").append(out.size()).append('\n');
        sb.append("Already correct:    ").append(unchanged).append('\n');
        if (onlyEmptyNumbers) sb.append("Skipped (had SIM):  ").append(skippedSet).append('\n');
        sb.append("Conflicts skipped:  ").append(conflicts.size()).append("\n\n");
        if (!conflicts.isEmpty()) {
            sb.append("In labels set to different SIMs:\n");
            for (Long cid : conflicts) sb.append("  • ").append(names.get(cid)).append('\n');
            sb.append('\n');
        }
        int shown = 0;
        for (Change ch : out) {
            if (shown++ >= 300) {
                sb.append("…and ").append(out.size() - 300).append(" more\n");
                break;
            }
            sb.append(ch.name).append(" · ").append(ch.number).append("\n   ")
                    .append(simLabelFor(ch.oldComp, ch.oldId)).append("  →  ")
                    .append(simLabelFor(ch.newComp, ch.newId)).append('\n');
        }
        if (!out.isEmpty()) sb.append("\nTap Apply to write these. A backup is saved first.");
        return sb.toString();
    }

    private void confirmApply() {
        if (pending == null) {
            toast("Tap Preview first.");
            return;
        }
        if (pending.isEmpty()) {
            toast("Nothing to change.");
            return;
        }
        List<Change> toApply = pending;
        new AlertDialog.Builder(this)
                .setTitle("Apply changes?")
                .setMessage("Update " + toApply.size() + " phone number(s)? A backup is saved first "
                        + "and can be restored from this screen.")
                .setPositiveButton("Apply", (d, w) -> runApply(toApply))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runApply(List<Change> toApply) {
        bulkOutput.setText("Saving backup and applying…");
        pending = null;
        new Thread(() -> {
            try {
                File backup = writeBackup(toApply);
                int n = applyChanges(toApply);
                ui.post(() -> bulkOutput.setText("Done. " + n + " number(s) updated.\nBackup: "
                        + backup.getAbsolutePath() + "\n\nPlace a test call to someone in one of "
                        + "the labels to confirm the dialler uses the right SIM."));
            } catch (Exception e) {
                ui.post(() -> bulkOutput.setText("Apply failed: " + e
                        + "\n\nAny batches written before the error can be undone with Restore."));
            }
        }).start();
    }

    private int applyChanges(List<Change> changes) throws Exception {
        ContentResolver cr = getContentResolver();
        int total = 0;
        for (int start = 0; start < changes.size(); start += 200) {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            for (Change ch : changes.subList(start, Math.min(changes.size(), start + 200))) {
                ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                        .withSelection(Data._ID + "=?", new String[]{String.valueOf(ch.dataId)})
                        .withValue(COL_COMP, ch.newComp)
                        .withValue(COL_ID, ch.newId)
                        .build());
            }
            ContentProviderResult[] res = cr.applyBatch(ContactsContract.AUTHORITY, ops);
            for (ContentProviderResult r : res) {
                if (r.count != null) total += r.count;
            }
        }
        return total;
    }

    private File backupDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "backups");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private File writeBackup(List<Change> changes) throws Exception {
        JSONArray arr = new JSONArray();
        for (Change ch : changes) {
            JSONObject o = new JSONObject();
            o.put("dataId", ch.dataId);
            o.put("name", ch.name == null ? "" : ch.name);
            o.put("number", ch.number == null ? "" : ch.number);
            o.put("comp", ch.oldComp == null ? JSONObject.NULL : ch.oldComp);
            o.put("id", ch.oldId == null ? JSONObject.NULL : ch.oldId);
            arr.put(o);
        }
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(new Date());
        File f = new File(backupDir(), "backup-" + ts + ".json");
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(arr.toString(1).getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private File latestBackup() {
        File[] files = backupDir().listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".json"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        return files[0];
    }

    private void confirmRestore() {
        File f = latestBackup();
        if (f == null) {
            toast("No backups yet.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Restore backup?")
                .setMessage("Put back the SIM settings saved in " + f.getName()
                        + " (from before that Apply)?")
                .setPositiveButton("Restore", (d, w) -> runRestore(f))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runRestore(File f) {
        if (bulkOutput != null) bulkOutput.setText("Restoring…");
        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray(new String(Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8));
                List<Change> changes = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Change ch = new Change();
                    ch.dataId = o.getLong("dataId");
                    ch.newComp = o.isNull("comp") ? null : o.getString("comp");
                    ch.newId = o.isNull("id") ? null : o.getString("id");
                    changes.add(ch);
                }
                int n = applyChanges(changes);
                ui.post(() -> {
                    if (bulkOutput != null) bulkOutput.setText("Restored " + n + " number(s) from "
                            + f.getName());
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (bulkOutput != null) bulkOutput.setText("Restore failed: " + e);
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ UI helpers

    private Button tab(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(6));
        content.addView(t);
        return t;
    }

    private TextView text(String s) {
        return text(content, s);
    }

    private TextView text(ViewGroup parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(4), 0, dp(4));
        parent.addView(t);
        return t;
    }

    private TextView mono(ViewGroup parent, String s) {
        TextView t = text(parent, s);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        content.addView(b);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void alert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("OK", null).show();
    }
}
