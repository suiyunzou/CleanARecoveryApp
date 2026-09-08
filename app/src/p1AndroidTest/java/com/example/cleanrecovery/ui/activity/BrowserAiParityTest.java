package com.example.cleanrecovery.ui.activity;

import android.app.Dialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.ui.browser.BrowserAiClient;
import com.example.cleanrecovery.ui.browser.BrowserAiStore;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.BrowserAiPrompt;
import com.example.cleanrecovery.ui.browser.TabManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserAiParityTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    private BrowserAiStore store() {
        assertTrue(instrumentation.getTargetContext().getPackageName().endsWith(".p1test"));
        return BrowserAiStore.getInstance(instrumentation.getTargetContext());
    }

    @Test public void topicsKeepOrderedContextAndClearOnlyTheirOwnConversation() throws Exception {
        BrowserAiStore store = store();
        BrowserAiStore.Topic first = store.create("same title", "system instruction");
        BrowserAiStore.Topic second = store.create("same title", "other instruction");
        try {
            store.append(first.id, "user", "remember this", "");
            store.append(first.id, "assistant", "remembered", "private reasoning");
            store.append(first.id, "user", "what did I say?", "");
            store.close();
            JSONArray context = store.requestMessages(first.id);
            assertEquals("Reopening storage must retain the full ordered model context", 4, context.length());
            assertEquals("system", context.getJSONObject(0).getString("role"));
            assertEquals("remembered", context.getJSONObject(2).getString("content"));
            assertFalse("Internal reasoning must not be copied into ordinary assistant history", context.toString().contains("private reasoning"));
            assertEquals("private reasoning", store.messages(first.id).get(2).reasoning);
            store.clear(first.id);
            assertEquals("Clearing messages retains the topic's system instruction", 1, store.messages(first.id).size());
            store.delete(first.id);
            assertTrue("Deleting a topic must remove its messages", store.messages(first.id).isEmpty());
            assertEquals("Equal topic names must not mix their histories", 1, store.messages(second.id).size());
        } finally { store.delete(first.id); store.delete(second.id); }
    }

    @Test public void streamDeliversIncrementalTextAndReasoningAndSendsHistory() throws Exception {
        try (Fixture fixture = new Fixture(1, false)) {
            JSONArray history = new JSONArray().put(new JSONObject().put("role", "system").put("content", "rules"))
                    .put(new JSONObject().put("role", "user").put("content", "first"))
                    .put(new JSONObject().put("role", "assistant").put("content", "previous reply"))
                    .put(new JSONObject().put("role", "user").put("content", "second"));
            List<String> updates = new ArrayList<>();
            new BrowserAiClient.Request().run(fixture.endpoint(), "test-key", "test-model", history,
                    (content, reasoning) -> updates.add(reasoning + ":" + content));
            assertEquals("Thinking must arrive before answer text", "thinking:", updates.get(0));
            assertTrue("The answer must be observable while streaming", updates.contains("thinking:hello"));
            assertEquals("thinking:hello world", updates.get(updates.size() - 1));
            fixture.await();
            JSONObject sent = fixture.requests.get(0);
            assertTrue(sent.getBoolean("stream"));
            assertEquals(history.toString(), sent.getJSONArray("messages").toString());
        }
    }

    @Test public void promptEditorPersistsBothKindsAndCopiesVariablesWithoutEditingContent() throws Exception {
        BrowserAiStore store = store();
        BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        List<String> created = new ArrayList<>();
        try {
            long focusDeadline = android.os.SystemClock.uptimeMillis() + 5000;
            while (!main(settings::hasWindowFocus) && android.os.SystemClock.uptimeMillis() < focusDeadline)
                android.os.SystemClock.sleep(30);
            assertTrue("Android only permits clipboard reads from the focused application", main(settings::hasWindowFocus));
            main(() -> {
                Class page = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
                java.lang.reflect.Method open = BrowserSettingsActivity.class.getDeclaredMethod("open", page); open.setAccessible(true);
                open.invoke(settings, Enum.valueOf(page, "AI_PROMPTS"));
                java.lang.reflect.Method editor = BrowserSettingsActivity.class.getDeclaredMethod("openAiPromptEditor", BrowserAiPrompt.class, int.class);
                editor.setAccessible(true);
                for (int type = 1; type <= 2; type++) {
                    editor.invoke(settings, null, type);
                    View root = settings.getWindow().getDecorView();
                    ((EditText) find(root, "标题")).setText("editor parity " + type);
                    EditText content = (EditText) find(root, "内容");
                    EditText name = (EditText) find(root, "editor parity " + type);
                    name.requestFocus(); name.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
                    assertTrue("The title's Next action must focus the prompt body", content.hasFocus());
                    assertEquals("Prompt variables must not be rewritten by keyboard suggestions", android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                            content.getInputType() & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    assertEquals("The keyboard must allow line breaks while drafting", android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                            content.getInputType() & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                    content.setText("before\n\tafter"); content.setSelection(7);
                    String variable = type == 1 ? "{{webpage_content}}" : "{{input}}";
                    View variableRow = find(root, variable);
                    while (!variableRow.isClickable()) variableRow = (View) variableRow.getParent();
                    variableRow.performClick();
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) settings.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    assertEquals("Via copies variables so users can paste them where needed", variable,
                            clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                    assertEquals("Copying a variable must not replace the user's draft", "before\n\tafter", content.getText().toString());
                    find(root, "保存").performClick();
                    assertNotNull("Saving must return to the grouped prompt list", find(settings.getWindow().getDecorView(), "AI 提示词"));
                    for (BrowserAiPrompt prompt : store.prompts()) if (prompt.name.equals("editor parity " + type)) {
                        created.add(prompt.id);
                        assertEquals(type, prompt.type);
                        assertEquals("before after", prompt.content);
                    }
                }
                assertEquals(2, created.size());
                return null;
            });
        } finally {
            main(() -> { settings.finish(); return null; });
            for (String id : created) store.deletePrompt(id);
        }
    }

    @Test public void leavingPromptEditorOffersSaveOrDiscardLikeVia() throws Exception {
        BrowserAiStore store = store();
        BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        List<String> created = new ArrayList<>();
        try {
            for (boolean save : new boolean[]{false, true}) {
                main(() -> {
                    Class page = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
                    java.lang.reflect.Method open = BrowserSettingsActivity.class.getDeclaredMethod("open", page); open.setAccessible(true);
                    open.invoke(settings, Enum.valueOf(page, "AI_PROMPTS"));
                    java.lang.reflect.Method editor = BrowserSettingsActivity.class.getDeclaredMethod("openAiPromptEditor", BrowserAiPrompt.class, int.class);
                    editor.setAccessible(true); editor.invoke(settings, null, 2);
                    View root = settings.getWindow().getDecorView();
                    ((EditText) find(root, "标题")).setText("back draft parity");
                    ((EditText) find(root, "内容")).setText("Keep {{input}}");
                    settings.onBackPressed();
                    return null;
                });
                instrumentation.waitForIdleSync();
                android.view.accessibility.AccessibilityNodeInfo window = instrumentation.getUiAutomation().getRootInActiveWindow();
                long deadline = android.os.SystemClock.uptimeMillis() + 5000;
                while ((window == null || window.findAccessibilityNodeInfosByText("修改的内容未保存，是否保存？").isEmpty())
                        && android.os.SystemClock.uptimeMillis() < deadline) {
                    android.os.SystemClock.sleep(50);
                    window = instrumentation.getUiAutomation().getRootInActiveWindow();
                }
                assertNotNull("Wait for the actual confirmation window to become accessible", window);
                assertFalse(window.findAccessibilityNodeInfosByText("修改的内容未保存，是否保存？").isEmpty());
                assertTrue("Via has two actions; a third discard action changes its back flow", window.findAccessibilityNodeInfosByText("不保存").isEmpty());
                String action = save ? "保存" : "取消";
                boolean clicked = false;
                for (android.view.accessibility.AccessibilityNodeInfo node : window.findAccessibilityNodeInfosByText(action)) {
                    if (action.contentEquals(node.getText()) && node.isClickable()) {
                        clicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); break;
                    }
                }
                assertTrue(clicked); instrumentation.waitForIdleSync();
                assertNotNull(main(() -> find(settings.getWindow().getDecorView(), "AI 提示词")));
                for (BrowserAiPrompt prompt : store.prompts()) if (prompt.name.equals("back draft parity")) created.add(prompt.id);
                assertEquals("Cancel discards and exits; save persists before returning", save ? 1 : 0, created.size());
            }
        } finally {
            main(() -> { settings.finish(); return null; });
            for (String id : created) store.deletePrompt(id);
        }
    }

    @Test public void databaseUpgradeAddsPromptsWithoutLosingExistingConversation() {
        try (android.database.sqlite.SQLiteDatabase database = android.database.sqlite.SQLiteDatabase.create(null)) {
            database.execSQL("CREATE TABLE threads (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            database.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, reasoning TEXT NOT NULL DEFAULT '')");
            database.execSQL("INSERT INTO threads VALUES ('old', 'Old topic', 1)");
            database.execSQL("INSERT INTO messages(thread_id,role,content) VALUES ('old','user','Keep this')");
            store().onUpgrade(database, 1, 4);
            try (android.database.Cursor cursor = database.rawQuery("SELECT content,flags FROM messages WHERE thread_id='old'", null)) {
                assertTrue(cursor.moveToFirst()); assertEquals("Keep this", cursor.getString(0));
                assertEquals("Existing conversation messages must remain valid model context after migration", 0, cursor.getInt(1));
            }
            database.execSQL("UPDATE threads SET page_url='https://old.test/' WHERE id='old'");
            database.execSQL("INSERT INTO prompts VALUES ('new','Title','Body',1)");
            database.execSQL("INSERT INTO providers VALUES ('provider','Name','https://provider.test','test-key','model','model')");
        }
    }

    @Test public void providerDraftDoesNotReplaceActiveCredentialsAndSurvivesModelEditsAndRecreation() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        java.util.Set<String> originalIds = new java.util.HashSet<>();
        for (BrowserAiStore.Provider provider : prefs.aiProviders()) originalIds.add(provider.id);
        String oldId = prefs.aiProviderId();
        String oldName = prefs.aiProviderName(), oldEndpoint = prefs.aiEndpoint(), oldKey = prefs.aiApiKey();
        String oldModels = prefs.aiModels(), oldModel = prefs.aiModel();
        prefs.setAiProviderName("original"); prefs.setAiEndpoint("https://original.test"); prefs.setAiApiKey("original-test-key");
        prefs.setAiModels("old-model"); prefs.setAiModel("old-model");
        try (androidx.test.core.app.ActivityScenario<BrowserSettingsActivity> scenario =
                     androidx.test.core.app.ActivityScenario.launch(BrowserSettingsActivity.class)) {
            scenario.onActivity(settings -> {
                try {
                    java.lang.reflect.Method start = BrowserSettingsActivity.class.getDeclaredMethod("startAiProviderDraft", String.class, String.class, String.class);
                    start.setAccessible(true); start.invoke(settings, "new provider", "https://draft.test", "one\ntwo");
                    View root = settings.getWindow().getDecorView();
                    assertEquals("A new endpoint must never inherit the active provider's secret", "", ((EditText) find(root, "API 密钥")).getText().toString());
                    ((EditText) find(root, "new provider")).setText("edited draft");
                    ((EditText) find(root, "API 密钥")).setText("draft-test-key");
                    View model = find(root, "two"); while (find(model, "删除") == null) model = (View) model.getParent();
                    assertFalse("Provider model rows are not active-model selectors", model.isClickable());
                    find(model, "删除").performClick();
                    assertNotNull("Model changes must preserve unsaved field edits", find(settings.getWindow().getDecorView(), "edited draft"));
                    assertEquals("Starting and editing a draft must not redirect active chat requests", "https://original.test", prefs.aiEndpoint());
                    assertEquals("original-test-key", prefs.aiApiKey()); assertEquals("old-model", prefs.aiModel());
                } catch (Exception e) { throw new AssertionError(e); }
            });
            scenario.recreate();
            scenario.onActivity(settings -> {
                View root = settings.getWindow().getDecorView();
                assertNotNull(find(root, "edited draft")); assertNotNull(find(root, "draft-test-key"));
                find(root, "保存").performClick();
                assertEquals("Saving a new provider must not select it implicitly", "https://original.test", prefs.aiEndpoint());
                BrowserAiStore.Provider created = null;
                for (BrowserAiStore.Provider provider : prefs.aiProviders()) if (!originalIds.contains(provider.id)) created = provider;
                assertNotNull("Save must create an independent provider record", created);
                prefs.selectAiProvider(created);
                assertEquals("edited draft", prefs.aiProviderName()); assertEquals("https://draft.test", prefs.aiEndpoint());
                assertEquals("draft-test-key", prefs.aiApiKey()); assertEquals("one", prefs.aiModel()); assertEquals("one", prefs.aiModels());
            });
        } finally {
            for (BrowserAiStore.Provider provider : prefs.aiProviders()) if (!originalIds.contains(provider.id)) prefs.aiStore().deleteProvider(provider.id);
            prefs.selectAiProvider(new BrowserAiStore.Provider(oldId, oldName, oldEndpoint, oldKey, oldModels, oldModel));
            prefs.setAiProviderName(oldName); prefs.setAiEndpoint(oldEndpoint); prefs.setAiApiKey(oldKey);
            prefs.setAiModels(oldModels); prefs.setAiModel(oldModel);
        }
    }

    @Test public void recreatingSettingsKeepsPromptDraftAndClosesTheOldMenuWindow() throws Exception {
        try (androidx.test.core.app.ActivityScenario<BrowserSettingsActivity> scenario =
                     androidx.test.core.app.ActivityScenario.launch(BrowserSettingsActivity.class)) {
            scenario.onActivity(settings -> {
                try {
                    Class page = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
                    java.lang.reflect.Method open = BrowserSettingsActivity.class.getDeclaredMethod("open", page); open.setAccessible(true);
                    open.invoke(settings, Enum.valueOf(page, "AI_PROMPTS"));
                    java.lang.reflect.Method editor = BrowserSettingsActivity.class.getDeclaredMethod("openAiPromptEditor", BrowserAiPrompt.class, int.class);
                    editor.setAccessible(true); editor.invoke(settings, null, 2);
                    ((EditText) find(settings.getWindow().getDecorView(), "标题")).setText("unsaved rotation draft");
                    ((EditText) find(settings.getWindow().getDecorView(), "内容")).setText("first\n{{input}}");
                } catch (Exception e) { throw new AssertionError(e); }
            });
            scenario.recreate();
            Dialog[] oldMenu = new Dialog[1];
            scenario.onActivity(settings -> {
                View root = settings.getWindow().getDecorView();
                assertNotNull("Recreation must preserve the unsaved title", find(root, "unsaved rotation draft"));
                assertNotNull("Line breaks are flattened on save, never merely on rotation", find(root, "first\n{{input}}"));
                assertNotNull("A message-template draft must retain its kind", find(root, "{{input}}"));
                assertNull(find(root, "{{webpage_url}}"));
                try {
                    java.lang.reflect.Method show = BrowserSettingsActivity.class.getDeclaredMethod("showAiPromptMenu",
                            View.class, String[].class, java.util.function.IntConsumer.class); show.setAccessible(true);
                    show.invoke(settings, find(root, "保存"), new String[]{"系统提示词", "消息模板"}, (java.util.function.IntConsumer) index -> {});
                    java.lang.reflect.Field menu = BrowserSettingsActivity.class.getDeclaredField("aiPromptMenu"); menu.setAccessible(true);
                    oldMenu[0] = (Dialog) menu.get(settings); assertTrue(oldMenu[0].isShowing());
                } catch (Exception e) { throw new AssertionError(e); }
            });
            scenario.recreate();
            assertFalse("The replaced activity must not leave its menu window behind", oldMenu[0].isShowing());
        }
    }

    @Test public void selectingProvidersKeepsSeparateCredentialsAndNoneKeepsSavedRecords() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        prefs.aiProviders();
        BrowserAiStore.Provider previous = new BrowserAiStore.Provider(prefs.aiProviderId(), prefs.aiProviderName(),
                prefs.aiEndpoint(), prefs.aiApiKey(), prefs.aiModels(), prefs.aiModel());
        BrowserAiStore store = prefs.aiStore();
        BrowserAiStore.Provider first = store.saveProvider(null, "provider A parity", "https://a.test", "a-test-key", "a-model", "a-model");
        BrowserAiStore.Provider second = store.saveProvider(null, "provider B parity", "https://b.test", "b-test-key", "b-model", "b-model");
        BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(() -> {
                Class page = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
                java.lang.reflect.Method open = BrowserSettingsActivity.class.getDeclaredMethod("open", page); open.setAccessible(true);
                open.invoke(settings, Enum.valueOf(page, "AI_PROVIDERS"));
                for (BrowserAiStore.Provider selected : new BrowserAiStore.Provider[]{first, second, first}) {
                    View row = find(settings.getWindow().getDecorView(), selected.name);
                    while (!row.isClickable()) row = (View) row.getParent(); row.performClick();
                    assertEquals(selected.endpoint, prefs.aiEndpoint()); assertEquals(selected.key, prefs.aiApiKey());
                    assertEquals(selected.model, prefs.aiModel()); assertEquals(selected.id, prefs.aiProviderId());
                }
                View none = find(settings.getWindow().getDecorView(), "无");
                while (!none.isClickable()) none = (View) none.getParent(); none.performClick();
                assertEquals("Disabling AI must remove active credentials", "", prefs.aiApiKey());
                assertEquals("", prefs.aiEndpoint());
                assertNotNull("Choosing None must preserve saved configurations", find(settings.getWindow().getDecorView(), first.name));
                assertNotNull(find(settings.getWindow().getDecorView(), second.name));
                return null;
            });
        } finally {
            main(() -> { settings.finish(); return null; });
            store.deleteProvider(first.id); store.deleteProvider(second.id); prefs.selectAiProvider(previous);
        }
    }

    @Test public void modelPreferenceSurvivesUnsupportedProvidersAndDisablingAi() {
        android.content.Context context = instrumentation.getTargetContext();
        android.content.SharedPreferences storage = context.getSharedPreferences("via_browser_prefs", 0);
        BrowserPrefs prefs = new BrowserPrefs(context);
        BrowserAiStore.Provider previous = new BrowserAiStore.Provider(prefs.aiProviderId(), prefs.aiProviderName(),
                prefs.aiEndpoint(), prefs.aiApiKey(), prefs.aiModels(), prefs.aiModel());
        String preference = storage.getString("ai_preferred_model", null);
        BrowserAiStore.Provider first = new BrowserAiStore.Provider("model-scope-a", "A", "https://a.test", "a-key", "first\nshared", "first");
        BrowserAiStore.Provider second = new BrowserAiStore.Provider("model-scope-b", "B", "https://b.test", "b-key", "fallback\nother", "other");
        BrowserAiStore.Provider third = new BrowserAiStore.Provider("model-scope-c", "C", "https://c.test", "c-key", "another\nshared", "another");
        try {
            prefs.selectAiProvider(first); prefs.setAiModel("shared");
            prefs.selectAiProvider(second);
            assertEquals("Unsupported choices fall back to the first model, not a provider-specific saved selection", "fallback", prefs.aiModel());
            prefs = new BrowserPrefs(context); prefs.selectAiProvider(first);
            assertEquals("A temporary fallback must not overwrite the user's remembered choice", "shared", prefs.aiModel());
            prefs.selectAiProvider(null);
            assertEquals("Disabled AI has no effective model", "", prefs.aiModel());
            prefs = new BrowserPrefs(context); prefs.selectAiProvider(third);
            assertEquals("The remembered model applies across providers that support the same ID", "shared", prefs.aiModel());
            prefs.setAiModel("another"); prefs.selectAiProvider(first);
            assertEquals("An explicit later choice replaces the global preference", "first", prefs.aiModel());
            prefs.selectAiProvider(third); assertEquals("another", prefs.aiModel());
        } finally {
            prefs.selectAiProvider(previous);
            storage.edit().putString("ai_model", previous.model).putString("ai_preferred_model", preference).apply();
        }
    }

    @Test public void builtInProviderDraftsExposeTheirOwnModelsWithoutBorrowingCredentials() throws Exception {
        BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(() -> {
                java.lang.reflect.Method start = BrowserSettingsActivity.class.getDeclaredMethod("startAiProviderDraft", String.class, String.class, String.class);
                start.setAccessible(true);
                for (com.example.cleanrecovery.ui.browser.BrowserAiProviderPresets.Preset preset :
                        com.example.cleanrecovery.ui.browser.BrowserAiProviderPresets.ALL) {
                    start.invoke(settings, preset.name, preset.endpoint, preset.models);
                    View root = settings.getWindow().getDecorView();
                    assertEquals("Every new service starts with its own empty secret", "", ((EditText) find(root, "API 密钥")).getText().toString());
                    if (!preset.endpoint.isEmpty()) {
                        assertNotNull(find(root, preset.endpoint));
                        assertFalse("Built-in services must not open as model-less templates", preset.models.isEmpty());
                        for (String model : preset.models.split("\n")) assertNotNull("Missing preset model " + model, find(root, model));
                    }
                    View management = find(root, "打开 API 密钥生成和管理页面");
                    while (management.getParent() instanceof View && management.getVisibility() == View.VISIBLE
                            && !management.isClickable()) management = (View) management.getParent();
                    assertEquals("Unknown services have no hardcoded management page", preset.keyPage == null ? View.GONE : View.VISIBLE, management.getVisibility());
                    settings.onBackPressed();
                }
                return null;
            });
        } finally { main(() -> { settings.finish(); return null; }); }
    }

    @Test public void providerSaveRejectsMissingFieldsAndIncludesTheUnsubmittedModel() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        prefs.aiProviders();
        int before = prefs.aiStore().providers().size();
        String selected = prefs.aiProviderId();
        List<String> created = new ArrayList<>();
        BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(() -> {
                java.lang.reflect.Method start = BrowserSettingsActivity.class.getDeclaredMethod("startAiProviderDraft", String.class, String.class, String.class);
                start.setAccessible(true); start.invoke(settings, "", "", "");
                String[] fields = {"aiProviderNameInput", "aiProviderEndpointInput", "aiProviderKeyInput", "aiProviderModelInput"};
                String[] values = {"validation provider parity", " https://validation.test/v1/chat/completions ", " test-key ", " pending-model "};
                for (int i = 0; i < fields.length; i++) {
                    find(settings.getWindow().getDecorView(), "保存").performClick();
                    java.lang.reflect.Field field = BrowserSettingsActivity.class.getDeclaredField(fields[i]); field.setAccessible(true);
                    EditText input = (EditText) field.get(settings);
                    assertTrue("Save must focus the first missing required field", input.hasFocus());
                    assertNotNull(input.getError());
                    assertEquals("Invalid drafts must not create unusable service records", before, prefs.aiStore().providers().size());
                    input.setText(values[i]);
                }
                find(settings.getWindow().getDecorView(), "保存").performClick();
                for (BrowserAiStore.Provider provider : prefs.aiStore().providers()) if (provider.name.equals(values[0])) {
                    created.add(provider.id);
                    assertEquals("https://validation.test/v1", provider.endpoint);
                    assertEquals("test-key", provider.key);
                    assertEquals("Saving includes the typed model without requiring the plus button first", "pending-model", provider.models);
                    assertEquals("pending-model", provider.model);
                }
                assertEquals(1, created.size()); assertEquals(selected, prefs.aiProviderId());
                return null;
            });
        } finally {
            main(() -> { settings.finish(); return null; });
            for (String id : created) prefs.aiStore().deleteProvider(id);
        }
    }

    @Test public void providerBackOnlyPromptsForChangesAndCancelDiscardsModelEdits() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext()); prefs.aiProviders();
        int count = prefs.aiStore().providers().size();
        BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(() -> {
                java.lang.reflect.Method start = BrowserSettingsActivity.class.getDeclaredMethod("startAiProviderDraft", String.class, String.class, String.class);
                start.setAccessible(true);
                start.invoke(settings, "discard provider parity", "https://discard.test", "model");
                settings.onBackPressed();
                java.lang.reflect.Field current = BrowserSettingsActivity.class.getDeclaredField("current"); current.setAccessible(true);
                assertEquals("An untouched preset must return without a save prompt", "ROOT", current.get(settings).toString());
                start.invoke(settings, "discard provider parity", "https://discard.test", "model");
                find(settings.getWindow().getDecorView(), "删除").performClick();
                settings.onBackPressed();
                return null;
            });
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            android.view.accessibility.AccessibilityNodeInfo root;
            do {
                root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null && !root.findAccessibilityNodeInfosByText("修改的内容未保存，是否保存？").isEmpty()) break;
                android.os.SystemClock.sleep(50);
            } while (android.os.SystemClock.uptimeMillis() < deadline);
            assertNotNull(root);
            assertFalse("Deleting a model must count as an unsaved change", root.findAccessibilityNodeInfosByText("修改的内容未保存，是否保存？").isEmpty());
            boolean cancelled = false;
            for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("取消")) {
                if ("取消".contentEquals(node.getText()) && node.isClickable()) cancelled = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            }
            assertTrue(cancelled); instrumentation.waitForIdleSync();
            main(() -> {
                java.lang.reflect.Field current = BrowserSettingsActivity.class.getDeclaredField("current"); current.setAccessible(true);
                assertEquals("Cancel means discard and return in Via's save dialog", "ROOT", current.get(settings).toString());
                assertEquals(count, prefs.aiStore().providers().size());
                return null;
            });
        } finally { main(() -> { settings.finish(); return null; }); }
    }

    @Test public void providerValidationStreamsViasProbeWithTheFirstAddedModelAndShowsItsResult() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext()); prefs.aiProviders();
        int before = prefs.aiStore().providers().size();
        String active = prefs.aiProviderId();
        for (boolean failure : new boolean[]{false, true}) {
            BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.startActivitySync(new Intent(
                    instrumentation.getTargetContext(), BrowserSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try (Fixture fixture = new Fixture(1, true, failure ? "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" : null)) {
                main(() -> {
                    java.lang.reflect.Method start = BrowserSettingsActivity.class.getDeclaredMethod("startAiProviderDraft", String.class, String.class, String.class);
                    start.setAccessible(true); start.invoke(settings, "validation request parity", fixture.endpoint(), "first-model\nsecond-model");
                    ((EditText) find(settings.getWindow().getDecorView(), "API 密钥")).setText(" test-key ");
                    java.lang.reflect.Field pending = BrowserSettingsActivity.class.getDeclaredField("aiProviderModelInput"); pending.setAccessible(true);
                    ((EditText) pending.get(settings)).setText("unadded-model");
                    find(settings.getWindow().getDecorView(), "验证").performClick();
                    return null;
                });
                java.lang.reflect.Field result = BrowserSettingsActivity.class.getDeclaredField("aiProviderValidationDialog"); result.setAccessible(true);
                Dialog dialog = null; long deadline = android.os.SystemClock.uptimeMillis() + 5000;
                while (dialog == null && android.os.SystemClock.uptimeMillis() < deadline) {
                    dialog = main(() -> (Dialog) result.get(settings));
                    if (dialog == null) android.os.SystemClock.sleep(50);
                }
                assertNotNull("Validation must report the first result without waiting for the held stream to finish", dialog);
                fixture.release.countDown(); fixture.await();
                JSONObject request = fixture.requests.get(0);
                assertTrue("Validation must exercise the streaming transport used for chat", request.getBoolean("stream"));
                assertEquals("first-model", request.getString("model"));
                JSONArray messages = request.getJSONArray("messages");
                assertEquals("Via sends no invented system prompt during validation", 1, messages.length());
                assertEquals("user", messages.getJSONObject(0).getString("role"));
                assertEquals("Say 'test'", messages.getJSONObject(0).getString("content"));
                Dialog shown = dialog;
                assertNotNull(main(() -> find(shown.getWindow().getDecorView(), failure ? "验证失败" : "验证成功")));
                if (failure) assertNotNull("An empty HTTP response must use Via's explicit Empty marker",
                        main(() -> find(shown.getWindow().getDecorView(), "HTTP 错误：401\n\nEmpty")));
                assertEquals("Validation must not save or select the draft", before, prefs.aiStore().providers().size());
                assertEquals(active, prefs.aiProviderId());
            } finally { main(() -> { settings.finish(); return null; }); }
        }
    }

    @Test public void deletingPromptFromItsMenuLeavesOtherPromptsAndSavedConversationIntact() throws Exception {
        BrowserAiStore store = store();
        BrowserAiPrompt first = store.savePrompt(null, "delete prompt parity", "Original system", 1);
        BrowserAiPrompt other = store.savePrompt(null, "keep prompt parity", "Keep", 2);
        BrowserAiStore.Topic topic = store.create("prompt snapshot parity", first.content);
        BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(() -> {
                Class page = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
                java.lang.reflect.Method open = BrowserSettingsActivity.class.getDeclaredMethod("open", page); open.setAccessible(true);
                open.invoke(settings, Enum.valueOf(page, "AI_PROMPTS"));
                return null;
            });
            instrumentation.waitForIdleSync();
            main(() -> {
                View row = find(settings.getWindow().getDecorView(), first.name);
                while (!row.isLongClickable()) row = (View) row.getParent();
                row.performLongClick(); return null;
            });
            instrumentation.getUiAutomation().waitForIdle(300, 5000);
            android.graphics.Bitmap popupImage = instrumentation.getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(settings.getExternalFilesDir(null), "prompt-delete-menu.png"))) {
                popupImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
            } finally { popupImage.recycle(); }
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            boolean clicked = false;
            while (!clicked && android.os.SystemClock.uptimeMillis() < deadline) {
                android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null) for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("删除")) {
                    if (!"删除".contentEquals(node.getText())) continue;
                    android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) {
                        long now = android.os.SystemClock.uptimeMillis();
                        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN,
                                bounds.centerX(), bounds.centerY(), 0);
                        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP,
                                bounds.centerX(), bounds.centerY(), 0);
                        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up); clicked = true; }
                        finally { down.recycle(); up.recycle(); }
                    }
                    if (clicked) break;
                }
                if (!clicked) android.os.SystemClock.sleep(50);
            }
            if (!clicked) {
                java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>();
                android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null) nodes.add(root);
                StringBuilder visible = new StringBuilder();
                while (!nodes.isEmpty()) {
                    android.view.accessibility.AccessibilityNodeInfo node = nodes.remove();
                    visible.append(node.getText()).append(" clickable=").append(node.isClickable()).append('\n');
                    for (int i = 0; i < node.getChildCount(); i++) if (node.getChild(i) != null) nodes.add(node.getChild(i));
                }
                fail("The prompt's long-press menu must offer deletion. Visible window:\n" + visible);
            }
            instrumentation.waitForIdleSync();
            long deletionDeadline = android.os.SystemClock.uptimeMillis() + 3000;
            boolean remains;
            do {
                remains = false;
                for (BrowserAiPrompt prompt : store.prompts()) if (prompt.id.equals(first.id)) remains = true;
                if (remains) android.os.SystemClock.sleep(50);
            } while (remains && android.os.SystemClock.uptimeMillis() < deletionDeadline);
            for (BrowserAiPrompt prompt : store.prompts()) assertNotEquals("Via deletes immediately after choosing the menu action", first.id, prompt.id);
            assertNotNull(main(() -> find(settings.getWindow().getDecorView(), other.name)));
            assertEquals("Deleting a reusable prompt must not rewrite an existing topic's system context", "Original system",
                    store.requestMessages(topic.id).getJSONObject(0).getString("content"));
        } finally {
            main(() -> { settings.finish(); return null; });
            store.deletePrompt(first.id); store.deletePrompt(other.id); store.delete(topic.id);
        }
    }

    @Test public void pageAiExtractsLiveContentUsesSystemPromptAndRestoresItsBoundTopic() throws Exception {
        BrowserAiStore store = store();
        BrowserAiPrompt prompt = store.savePrompt(null, "page system parity", "Read {{webpage_title}}: {{webpage_content}}", 1);
        BrowserActivity browser = (BrowserActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        BrowserPrefs prefs = new BrowserPrefs(browser);
        String endpoint = prefs.aiEndpoint(), provider = prefs.aiProviderName(), model = prefs.aiModel(), key = prefs.aiApiKey();
        String url = "https://ai-page.parity.test/";
        try (Fixture fixture = new Fixture(2, false)) {
            prefs.setAiEndpoint(fixture.endpoint()); prefs.setAiProviderName("local test"); prefs.setAiModel("test-model"); prefs.setAiApiKey("");
            java.lang.reflect.Field tabsField = BrowserActivity.class.getDeclaredField("tabs"); tabsField.setAccessible(true);
            android.webkit.WebView web = main(() -> ((TabManager) tabsField.get(browser)).current().webView);
            main(() -> { web.loadDataWithBaseURL(url, "<html><head><title>Page title</title></head><body>Page evidence</body></html>", "text/html", "UTF-8", null); return null; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!"\"Page evidence\"".equals(js(web, "document.body.innerText")) && System.nanoTime() < deadline) Thread.sleep(30);
            js(web, "document.title = 'Updated page title'");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"Updated page title".equals(main(web::getTitle)) && System.nanoTime() < deadline) Thread.sleep(30);
            assertEquals("The page can change its title without another navigation", "Updated page title", main(web::getTitle));
            java.lang.reflect.Method open = BrowserActivity.class.getDeclaredMethod("openPageAi"); open.setAccessible(true);
            java.lang.reflect.Field currentDialog = BrowserActivity.class.getDeclaredField("aiChatDialog"); currentDialog.setAccessible(true);
            main(() -> { open.invoke(browser); return null; });
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (main(() -> currentDialog.get(browser)) == null && System.nanoTime() < deadline) Thread.sleep(30);
            main(() -> {
                Dialog dialog = (Dialog) currentDialog.get(browser); assertNotNull(dialog);
                View root = dialog.getWindow().getDecorView();
                assertNotNull("Page-scoped prompts must be offered for a real webpage", find(root, prompt.name));
                find(root, prompt.name).performClick();
                ((EditText) find(root, "消息")).setText("Explain this"); find(root, "发送").performClick();
                return null;
            });
            fixture.await();
            JSONObject sent = null;
            for (JSONObject request : fixture.requests) if (request.getBoolean("stream")) sent = request;
            assertNotNull(sent);
            assertEquals("The page snapshot must include the live title, not the last navigation's cached title", "Read Updated page title: Page evidence",
                    sent.getJSONArray("messages").getJSONObject(0).getString("content"));
            BrowserAiStore.Topic topic = store.topicForPage(url); assertNotNull(topic);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (store.messages(topic.id).size() < 3 && System.nanoTime() < deadline) Thread.sleep(30);
            assertEquals(3, store.messages(topic.id).size());
            main(() -> { ((Dialog) currentDialog.get(browser)).dismiss(); open.invoke(browser); return null; });
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (main(() -> currentDialog.get(browser)) == null && System.nanoTime() < deadline) Thread.sleep(30);
            main(() -> {
                View root = ((Dialog) currentDialog.get(browser)).getWindow().getDecorView();
                assertNotNull("Reopening AI on the same URL must restore its conversation", find(root, "Explain this"));
                assertNull("Existing topics must not offer replacement system prompts", find(root, prompt.name));
                return null;
            });
        } finally {
            main(() -> { browser.finish(); return null; });
            prefs.setAiEndpoint(endpoint); prefs.setAiProviderName(provider); prefs.setAiModel(model); prefs.setAiApiKey(key);
            store.deletePrompt(prompt.id);
            BrowserAiStore.Topic topic = store.topicForPage(url); if (topic != null) store.delete(topic.id);
        }
    }

    private String js(android.webkit.WebView web, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); String[] value = {null};
        main(() -> { web.evaluateJavascript(script, result -> { value[0] = result; done.countDown(); }); return null; });
        assertTrue(done.await(5, TimeUnit.SECONDS)); return value[0];
    }

    @Test public void longConversationsKeepFullRecordsButUseViasRecentRequestWindow() throws Exception {
        BrowserAiStore store = store();
        BrowserAiStore.Topic topic = store.create("context window parity", "system");
        try {
            for (int i = 0; i < 30; i++) store.append(topic.id, i % 2 == 0 ? "user" : "assistant", "message " + i, "");
            JSONArray request = store.requestMessages(topic.id);
            assertEquals("Via retains system plus the recent 19 messages when sending", 20, request.length());
            assertEquals("message 11", request.getJSONObject(1).getString("content"));
            assertEquals("message 29", request.getJSONObject(19).getString("content"));
            assertEquals("Limiting requests must never delete the user's local history", 31, store.messages(topic.id).size());
            assertTrue("Export must include turns outside the model context window", store.exportText(topic.id).contains("## message 0\n\n"));
        } finally { store.delete(topic.id); }
    }

    @Test public void topicLongPressSelectsAndAnUnsentDraftDoesNotCreateARecord() throws Exception {
        BrowserAiStore store = store();
        BrowserAiStore.Topic first = store.create("select first parity", "");
        BrowserAiStore.Topic second = store.create("select second parity", "");
        BrowserActivity browser = (BrowserActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Dialog[] dialog = new Dialog[1];
        try {
            main(() -> {
                java.lang.reflect.Method show = BrowserActivity.class.getDeclaredMethod("showAiSheet"); show.setAccessible(true);
                dialog[0] = (Dialog) show.invoke(browser);
                View root = dialog[0].getWindow().getDecorView();
                find(root, first.name).performLongClick();
                find(root, second.name).performClick();
                assertNotNull("Long press must enter multi-selection instead of deleting immediately", find(root, "2"));
                assertNotNull(find(root, "删除"));
                find(root, "取消选择").performClick();
                assertNotNull(find(root, "所有主题"));
                dialog[0].dismiss();
                int count = store.topics().size();
                java.lang.reflect.Method open = BrowserActivity.class.getDeclaredMethod("openAiChat", BrowserAiStore.Topic.class); open.setAccessible(true);
                dialog[0] = (Dialog) open.invoke(browser, new Object[]{null});
                assertNotNull(find(dialog[0].getWindow().getDecorView(), "新主题"));
                dialog[0].dismiss();
                assertEquals("Opening and abandoning a new conversation must not leave empty topics", count, store.topics().size());
                return null;
            });
        } finally {
            main(() -> { if (dialog[0] != null) dialog[0].dismiss(); browser.finish(); return null; });
            store.delete(first.id); store.delete(second.id);
        }
    }

    @Test public void legacyNamesImportOnceWithoutLosingTopics() {
        BrowserAiStore store = store();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.add("legacy migration parity");
        try {
            store.importLegacy(names); store.importLegacy(names);
            long count = 0;
            for (BrowserAiStore.Topic topic : store.topics()) if (topic.id.equals("legacy:legacy migration parity")) count++;
            assertEquals("Retrying migration must not duplicate a user's topic", 1, count);
            store.append("legacy:legacy migration parity", "user", "retained", "");
            store.importLegacy(names);
            assertEquals("Reimporting a name must preserve messages already attached to it", 1,
                    store.messages("legacy:legacy migration parity").size());
        } finally { store.delete("legacy:legacy migration parity"); }
    }

    @Test public void markdownFormatsRepliesAndRoutesLinksWithoutExposingSyntax() throws Exception {
        main(() -> {
            String[] opened = {null};
            io.noties.markwon.Markwon renderer = com.example.cleanrecovery.ui.browser.BrowserAiMarkdown.create(
                    instrumentation.getTargetContext(), link -> opened[0] = link);
            TextView view = new TextView(instrumentation.getTargetContext());
            renderer.setMarkdown(view, "**bold answer**\n\n[read more](https://markdown.parity.test/article)\n\n"
                    + "| First | Second |\n| --- | --- |\n| one | two |");
            android.text.Spanned text = (android.text.Spanned) view.getText();
            assertTrue(text.toString().contains("bold answer"));
            assertFalse("Users must see formatted text rather than raw Markdown delimiters", text.toString().contains("**"));
            android.text.style.ClickableSpan[] links = text.getSpans(0, text.length(), android.text.style.ClickableSpan.class);
            assertEquals(1, links.length);
            links[0].onClick(view);
            assertEquals("Markdown links must use the browser's navigation consumer", "https://markdown.parity.test/article", opened[0]);
            boolean table = false;
            for (Object span : text.getSpans(0, text.length(), Object.class)) if (span.getClass().getName().contains("TableRowSpan")) table = true;
            assertTrue("AI tables must have native row rendering", table);
            return null;
        });
    }

    @Test public void messagePreviewCanSwitchToRawMarkdownAndDeletionChangesNextContext() throws Exception {
        BrowserAiStore store = store();
        BrowserAiStore.Topic topic = store.create("message preview parity", "system");
        long id = store.append(topic.id, "assistant", "**answer**", "reasoning");
        BrowserActivity browser = (BrowserActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Dialog[] dialog = new Dialog[1];
        try {
            main(() -> {
                io.noties.markwon.Markwon markdown = com.example.cleanrecovery.ui.browser.BrowserAiMarkdown.create(browser, link -> { });
                java.lang.reflect.Method preview = BrowserActivity.class.getDeclaredMethod("showAiMessagePreview",
                        BrowserAiStore.Message.class, io.noties.markwon.Markwon.class);
                preview.setAccessible(true);
                dialog[0] = (Dialog) preview.invoke(browser, store.messages(topic.id).get(1), markdown);
                View root = dialog[0].getWindow().getDecorView();
                find(root, "纯文本").performClick();
                assertNotNull("Raw preview must expose the saved source including reasoning quotes", find(root, ">reasoning\n\n**answer**"));
                find(root, "Markdown").performClick();
                assertNull("Switching back must remove literal formatting markers", find(root, ">reasoning\n\n**answer**"));
                assertNotNull(find(root, "纯文本"));
                return null;
            });
            store.deleteMessage("wrong-topic", id);
            assertEquals("Message actions must be scoped to their owning topic", 2, store.messages(topic.id).size());
            store.deleteMessage(topic.id, id);
            assertEquals("Deleted messages must no longer be sent to the model", 1, store.requestMessages(topic.id).length());
            store.deleteMessage(topic.id, store.messages(topic.id).get(0).id);
            assertEquals("Conversation message deletion must preserve the system instruction", 1, store.messages(topic.id).size());
        } finally {
            main(() -> { if (dialog[0] != null) dialog[0].dismiss(); browser.finish(); return null; });
            store.delete(topic.id);
        }
    }

    @Test public void failedChatPersistsAcrossReopeningWithoutSendingErrorTextBackToTheModel() throws Exception {
        BrowserAiStore store = store();
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String endpoint = prefs.aiEndpoint(), provider = prefs.aiProviderName(), model = prefs.aiModel(), key = prefs.aiApiKey();
        BrowserActivity browser = (BrowserActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Method open = BrowserActivity.class.getDeclaredMethod("openAiChat", BrowserAiStore.Topic.class); open.setAccessible(true);
        Dialog[] dialog = {null};
        try {
            for (boolean partial : new boolean[]{false, true}) {
                BrowserAiStore.Topic topic = store.create("failure persistence parity", "System");
                String response = partial
                        ? "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                        : "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                String expected = partial ? "partial" : "❗HTTP 错误：401\n\nEmpty";
                try (Fixture fixture = new Fixture(2, false, response)) {
                    prefs.setAiEndpoint(fixture.endpoint()); prefs.setAiProviderName("test"); prefs.setAiModel("model"); prefs.setAiApiKey("");
                    for (int turn = 0; turn < 2; turn++) {
                        main(() -> {
                            dialog[0] = (Dialog) open.invoke(browser, topic);
                            View root = dialog[0].getWindow().getDecorView();
                            if (store.messages(topic.id).size() > 1) assertNotNull("Reopening must preserve the visible failed reply", find(root, expected));
                            ((EditText) find(root, "消息")).setText("Try again"); find(root, "发送").performClick();
                            return null;
                        });
                        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
                        while (store.messages(topic.id).size() < 3 + turn * 2 && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(30);
                        List<BrowserAiStore.Message> messages = store.messages(topic.id);
                        assertEquals(3 + turn * 2, messages.size());
                        assertEquals(expected, messages.get(messages.size() - 1).content);
                        assertEquals("Only content-less failures are excluded from model context", partial ? 0 : 1, messages.get(messages.size() - 1).flags);
                        main(() -> { dialog[0].dismiss(); return null; });
                    }
                    fixture.await();
                    JSONArray next = fixture.requests.get(1).getJSONArray("messages");
                    assertEquals("Error messages must not be sent back as assistant answers", partial ? 4 : 3, next.length());
                    if (partial) assertEquals("partial", next.getJSONObject(2).getString("content"));
                    else assertFalse(next.toString().contains("HTTP"));
                    assertTrue("Local export must retain what the user saw", store.exportText(topic.id).contains(expected));
                } finally { store.delete(topic.id); }
            }
        } finally {
            main(() -> { if (dialog[0] != null) dialog[0].dismiss(); browser.finish(); return null; });
            prefs.setAiEndpoint(endpoint); prefs.setAiProviderName(provider); prefs.setAiModel(model); prefs.setAiApiKey(key);
        }
    }

    @Test public void httpErrorsAndTruncatedRepliesMustNotLookSuccessful() throws Exception {
        for (String response : new String[]{
                "HTTP/1.1 401 Unauthorized\r\nContent-Length: 7\r\nConnection: close\r\n\r\ndenied!",
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"}) {
            try (Fixture fixture = new Fixture(1, false, response)) {
                try {
                    new BrowserAiClient.Request().run(fixture.endpoint(), "", "model", new JSONArray(), (content, reasoning) -> { });
                    fail("A failed or interrupted backend reply must be reported to the user");
                } catch (java.io.IOException expected) {
                    if (response.contains("401")) assertEquals("HTTP errors must preserve the status and server response", "HTTP 错误：401\n\ndenied!", expected.getMessage());
                    else assertTrue(expected.getMessage().contains("意外中断"));
                }
                fixture.await();
            }
        }
    }

    @Test public void stopCancelsAnOpenStreamWithoutDeliveringMoreText() throws Exception {
        try (Fixture fixture = new Fixture(1, true)) {
            BrowserAiClient.Request request = new BrowserAiClient.Request();
            List<String> updates = new ArrayList<>();
            FutureTask<Void> task = new FutureTask<>(() -> {
                try { request.run(fixture.endpoint(), "", "model", new JSONArray(), (content, reasoning) -> {
                    updates.add(content); request.cancel();
                }); } catch (Exception error) { if (!request.isCancelled()) throw error; }
                return null;
            });
            new Thread(task).start();
            task.get(5, TimeUnit.SECONDS);
            assertTrue(request.isCancelled());
            assertEquals("Stopping must terminate before later server events", 1, updates.size());
        }
    }

    @Test public void reopenedChatShowsPreviousTurnsAndSendsThemOnTheNextTurn() throws Exception {
        BrowserAiStore store = store();
        BrowserAiStore.Topic topic = store.create("chat UI parity", "stay concise");
        BrowserAiPrompt template = store.savePrompt(null, "translate template parity", "Translate {{input}}", 2);
        BrowserActivity browser = (BrowserActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        BrowserPrefs prefs = new BrowserPrefs(browser);
        String endpoint = prefs.aiEndpoint(), provider = prefs.aiProviderName(), model = prefs.aiModel(), key = prefs.aiApiKey();
        Dialog[] dialog = new Dialog[1];
        try (Fixture fixture = new Fixture(2, false)) {
            prefs.setAiEndpoint(fixture.endpoint()); prefs.setAiProviderName("local test"); prefs.setAiModel("test-model"); prefs.setAiApiKey("");
            java.lang.reflect.Method open = BrowserActivity.class.getDeclaredMethod("openAiChat", BrowserAiStore.Topic.class);
            open.setAccessible(true);
            for (int turn = 1; turn <= 2; turn++) {
                final int index = turn;
                main(() -> {
                    dialog[0] = (Dialog) open.invoke(browser, topic);
                    View root = dialog[0].getWindow().getDecorView();
                    if (index == 2) {
                        assertNotNull("Reopening a topic must show its earlier answer", find(root, "thinking\n\nhello world"));
                        find(root, template.name).performClick();
                    }
                    EditText input = (EditText) find(root, "消息");
                    input.setText("question " + index);
                    find(root, "发送").performClick();
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (store.messages(topic.id).size() < 1 + turn * 2 && System.nanoTime() < deadline) Thread.sleep(30);
                assertEquals("Each UI send must persist both sides of the turn", 1 + turn * 2, store.messages(topic.id).size());
                main(() -> { dialog[0].dismiss(); return null; });
            }
            fixture.await();
            assertEquals("Second turn must include system + user + assistant + new user", 4,
                    fixture.requests.get(1).getJSONArray("messages").length());
            assertEquals("Selected message templates must change the actual outgoing user message", "Translate question 2",
                    fixture.requests.get(1).getJSONArray("messages").getJSONObject(3).getString("content"));
            assertEquals("Choosing a message template must preserve the existing topic's system instruction", "stay concise",
                    fixture.requests.get(1).getJSONArray("messages").getJSONObject(0).getString("content"));
        } finally {
            main(() -> { if (dialog[0] != null) dialog[0].dismiss(); browser.finish(); return null; });
            prefs.setAiEndpoint(endpoint); prefs.setAiProviderName(provider); prefs.setAiModel(model); prefs.setAiApiKey(key);
            store.delete(topic.id);
            store.deletePrompt(template.id);
        }
    }

    private static View find(View view, String text) {
        if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof TextView && (text.contentEquals(((TextView) view).getText())
                || text.contentEquals(((TextView) view).getHint() == null ? "" : ((TextView) view).getHint()))) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private <T> T main(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        instrumentation.runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final List<JSONObject> requests = new ArrayList<>();
        final FutureTask<Void> task;
        final CountDownLatch release = new CountDownLatch(1);
        Fixture(int count, boolean hold) throws Exception {
            this(count, hold, null);
        }
        Fixture(int count, boolean hold, String response) throws Exception {
            server = new ServerSocket(0);
            server.setSoTimeout(15000);
            task = new FutureTask<>(() -> {
                for (int i = 0; i < count; i++) try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    int length = 0; String line;
                    while (!(line = reader.readLine()).isEmpty()) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    char[] body = new char[length]; int at = 0, n;
                    while (at < length && (n = reader.read(body, at, length - at)) > 0) at += n;
                    requests.add(new JSONObject(new String(body, 0, at)));
                    java.io.OutputStream out = socket.getOutputStream();
                    if (response != null) { out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush(); continue; }
                    if (!requests.get(requests.size() - 1).getBoolean("stream")) {
                        byte[] title = "{\"choices\":[{\"message\":{\"content\":\"Generated topic\"}}]}".getBytes(StandardCharsets.UTF_8);
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + title.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(title); out.flush(); continue;
                    }
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.write(": heartbeat\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (hold) { release.await(10, TimeUnit.SECONDS); break; }
                    for (String text : new String[]{"hello", " world"}) {
                        out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                }
                return null;
            });
            new Thread(task, "ai-parity-server").start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getLocalPort(); }
        void await() throws Exception { task.get(10, TimeUnit.SECONDS); }
        @Override public void close() throws Exception { release.countDown(); server.close(); }
    }
}
