use serde_json::{Value, json};
use url::Url;
const APP: &str = "app.cbgpu.com";
pub fn bridge_origin(value: &str) -> bool {
    Url::parse(value).is_ok_and(|u| {
        u.scheme() == "https"
            && u.host_str() == Some(APP)
            && u.port_or_known_default() == Some(443)
            && u.username().is_empty()
            && u.password().is_none()
    })
}
pub fn bridge_document(value: &str) -> bool {
    if !bridge_origin(value) {
        return false;
    }
    let Ok(u) = Url::parse(value) else {
        return false;
    };
    let path = u.path().to_ascii_lowercase();
    !["/api", "/backend", "/downloads", "/_next", "/static"]
        .iter()
        .any(|prefix| path == *prefix || path.starts_with(&format!("{prefix}/")))
        && ![
            ".html", ".htm", ".svg", ".xml", ".pdf", ".js", ".mjs", ".json", ".apk",
        ]
        .iter()
        .any(|ext| path.ends_with(ext))
}
pub fn navigation(raw: &str) -> Value {
    if raw.len() > 16384 || raw.contains(['\r', '\n', '\\']) {
        return json!({"action":"deny"});
    }
    let Ok(mut u) = Url::parse(raw) else {
        return json!({"action":"deny"});
    };
    let host = u
        .host_str()
        .unwrap_or("")
        .trim_end_matches('.')
        .to_ascii_lowercase();
    if u.scheme() == "cbgpu" && host == "open" {
        let target = format!(
            "https://{APP}{}{}{}",
            u.path(),
            u.query().map(|q| format!("?{q}")).unwrap_or_default(),
            u.fragment().map(|f| format!("#{f}")).unwrap_or_default()
        );
        return navigation(&target);
    }
    let mode = match host.as_str() {
        "student.cbgpu.com" => Some("student"),
        "researcher.cbgpu.com" | "developer.cbgpu.com" => Some("researcher"),
        _ => None,
    };
    if ["http", "https"].contains(&u.scheme())
        && (mode.is_some()
            || ["cbgpu.com", "www.cbgpu.com"].contains(&host.as_str())
            || host == APP && !bridge_origin(raw))
    {
        let _ = u.set_scheme("https");
        let _ = u.set_host(Some(APP));
        let _ = u.set_port(None);
        let _ = u.set_username("");
        let _ = u.set_password(None);
        if u.path() == "/workspace" {
            u.set_path("/")
        };
        let query = u
            .query_pairs()
            .filter(|(k, _)| k != "native" && (mode.is_none() || k != "mode"))
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect::<Vec<_>>();
        u.set_query(None);
        {
            let mut pairs = u.query_pairs_mut();
            for (k, v) in query {
                pairs.append_pair(&k, &v);
            }
            if let Some(mode) = mode {
                pairs.append_pair("mode", mode);
            }
            pairs.append_pair("native", "1");
        }
        return json!({"action":"redirect","url":u.as_str()});
    }
    if u.scheme() == "https"
        && u.username().is_empty()
        && u.password().is_none()
        && u.port_or_known_default() == Some(443)
    {
        let tenant = host
            .strip_suffix(".front.cbgpu.com")
            .or_else(|| host.strip_suffix(".server.cbgpu.com"))
            .is_some_and(|s| {
                !s.is_empty()
                    && s.len() <= 63
                    && s.bytes()
                        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
                    && !s.starts_with('-')
                    && !s.ends_with('-')
            });
        if [
            APP,
            "docs.cbgpu.com",
            "arxiv.cbgpu.com",
            "latex.cbgpu.com",
            "gpu.cbgpu.com",
            "print.cbgpu.com",
            "laser.cbgpu.com",
            "manufacturing.cbgpu.com",
            "admin.cbgpu.com",
            "developers.cbgpu.com",
        ]
        .contains(&host.as_str())
            || tenant
        {
            return json!({"action":"internal","url":u.as_str(),"bridge":bridge_document(raw)});
        }
    }
    if ["https", "http", "mailto", "tel", "spotify"].contains(&u.scheme())
        && u.username().is_empty()
        && u.password().is_none()
    {
        json!({"action":"external","url":u.as_str()})
    } else {
        json!({"action":"deny"})
    }
}
pub fn valid_tab(path: &str) -> bool {
    if !path.starts_with('/') || path.starts_with("//") || path.contains(['\\', '\r', '\n']) {
        return false;
    }
    let Ok(u) = Url::parse(&format!("https://{APP}{path}")) else {
        return false;
    };
    ["/", "/ai", "/study", "/dashboard", "/services", "/profile"].contains(&u.path())
        && u.host_str() == Some(APP)
        && u.query_pairs()
            .all(|(k, v)| k == "mode" && ["student", "researcher"].contains(&v.as_ref()))
}
pub fn bridge_message(raw: &str) -> bool {
    if raw.len() > 32768 {
        return false;
    }
    let Ok(v) = serde_json::from_str::<Value>(raw) else {
        return false;
    };
    let Some(id) = v["callId"].as_str() else {
        return false;
    };
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"-_".contains(&c))
    {
        return false;
    }
    if !v["payload"].is_object() {
        return false;
    }
    match v["action"].as_str() {
        Some("invalidateSchoolWidgets") => v["payload"].as_object().is_some_and(|p| p.is_empty()),
        Some("setTimetableSelection") => {
            let p = &v["payload"];
            p.as_object().is_some_and(|p| p.len() == 4)
                && p["expected_user_id"].as_str().is_some_and(|s| {
                    s.len() == 36
                        && s.bytes().enumerate().all(|(i, b)| {
                            if [8, 13, 18, 23].contains(&i) {
                                b == b'-'
                            } else {
                                b.is_ascii_hexdigit()
                            }
                        })
                })
                && p["school_id"].as_str().is_some_and(|s| {
                    !s.is_empty()
                        && s.len() <= 100
                        && s.bytes()
                            .all(|c| c.is_ascii_alphanumeric() || b"-_".contains(&c))
                })
                && p["grade"].as_u64().is_some_and(|n| (1..=12).contains(&n))
                && p["class_num"]
                    .as_u64()
                    .is_some_and(|n| (1..=100).contains(&n))
        }

        Some("setNavigation") => {
            v["payload"]["visible"] == false
                || v["payload"]["tabs"].as_array().is_some_and(|tabs| {
                    (2..=6).contains(&tabs.len())
                        && tabs.iter().all(|t| {
                            t["path"].as_str().is_some_and(valid_tab)
                                && t["label"]
                                    .as_str()
                                    .is_some_and(|s| !s.is_empty() && s.len() <= 120)
                                && t["svg"].as_str().is_some_and(|s| s.len() <= 4000)
                        })
                })
        }
        Some(
            "getAppInfo"
            | "scheduleAlarm"
            | "cancelAlarm"
            | "cancelAllAlarms"
            | "listAlarms"
            | "requestNotificationPermission"
            | "syncSession"
            | "clearSession"
            | "getPersistedSession"
            | "loginEduset"
            | "downloadMaterial",
        ) => true,
        _ => false,
    }
}
#[cfg(target_os = "android")]
mod android {
    use super::*;
    use jni::{
        JNIEnv,
        objects::{JClass, JString},
        sys::{jboolean, jstring},
    };
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cbgpu_cbgpu_1app_NativeCore_route(
        mut env: JNIEnv,
        _: JClass,
        input: JString,
    ) -> jstring {
        let s: String = env.get_string(&input).map(Into::into).unwrap_or_default();
        env.new_string(navigation(&s).to_string())
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cbgpu_cbgpu_1app_NativeCore_bridgeAllowed(
        mut env: JNIEnv,
        _: JClass,
        input: JString,
    ) -> jboolean {
        let s: String = env.get_string(&input).map(Into::into).unwrap_or_default();
        bridge_document(&s).into()
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cbgpu_cbgpu_1app_NativeCore_validMessage(
        mut env: JNIEnv,
        _: JClass,
        input: JString,
    ) -> jboolean {
        let s: String = env.get_string(&input).map(Into::into).unwrap_or_default();
        bridge_message(&s).into()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn desktop_links_stay_mobile() {
        for h in [
            "cbgpu.com",
            "www.cbgpu.com",
            "student.cbgpu.com",
            "developer.cbgpu.com",
            "researcher.cbgpu.com",
        ] {
            let r = navigation(&format!("https://{h}/ai?q=x"));
            assert_eq!(r["action"], "redirect");
            assert!(
                r["url"]
                    .as_str()
                    .unwrap()
                    .starts_with("https://app.cbgpu.com/ai?")
            );
        }
    }
    #[test]
    fn bridge_never_reaches_other_origins() {
        for u in [
            "http://app.cbgpu.com",
            "https://app.cbgpu.com:444",
            "https://app.cbgpu.com.evil.test",
            "https://a.front.cbgpu.com",
            "https://gpu.cbgpu.com",
            "https://user@app.cbgpu.com",
        ] {
            assert!(!bridge_origin(u), "{u}")
        }
        assert!(bridge_origin("https://app.cbgpu.com/ai"));
    }
    #[test]
    fn script_and_file_navigation_are_denied() {
        for s in [
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://private",
            "intent://x",
        ] {
            assert_eq!(navigation(s)["action"], "deny")
        }
    }
    #[test]
    fn tabs_cannot_escape_owned_mobile_routes() {
        for s in [
            "//evil.test",
            "/profile?redirect=https://evil.test",
            "/backend",
            "/ai?mode=admin",
        ] {
            assert!(!valid_tab(s))
        }
        assert!(valid_tab("/ai?mode=researcher"));
    }
}

#[cfg(test)]
mod document_tests {
    use super::*;
    #[test]
    fn downloaded_documents_have_no_native_privileges() {
        for path in [
            "/backend/storage/v1/object/public/files/a.html",
            "/api/files/a/content",
            "/downloads/file.pdf",
            "/upload.svg",
            "/static/page.htm",
        ] {
            assert!(!bridge_document(&format!("https://app.cbgpu.com{path}")))
        }
        for path in ["/", "/ai", "/auth/callback", "/study?native=1"] {
            assert!(bridge_document(&format!("https://app.cbgpu.com{path}")))
        }
    }
}

#[cfg(test)]
mod school_widget_tests {
    use super::*;
    #[test]
    fn school_selection_requires_bounded_owner_bound_payload() {
        let mut message = serde_json::json!({"callId":"school-1","action":"setTimetableSelection","payload":{"school_id":"cbsh","expected_user_id":"12345678-1234-1234-1234-123456789abc","grade":2,"class_num":3}});
        assert!(bridge_message(&message.to_string()));
        for (key, value) in [
            ("school_id", serde_json::json!("../../private")),
            ("grade", serde_json::json!(13)),
            ("class_num", serde_json::json!(0)),
            ("expected_user_id", serde_json::json!("other")),
        ] {
            let original = message["payload"][key].clone();
            message["payload"][key] = value;
            assert!(!bridge_message(&message.to_string()), "{key}");
            message["payload"][key] = original;
        }
        message["payload"]["token"] = serde_json::json!("untrusted");
        assert!(!bridge_message(&message.to_string()));
    }
    #[test]
    fn school_invalidation_accepts_no_remote_parameters() {
        assert!(bridge_message(
            r#"{"callId":"x","action":"invalidateSchoolWidgets","payload":{}}"#
        ));
        assert!(!bridge_message(
            r#"{"callId":"x","action":"invalidateSchoolWidgets","payload":{"school_id":"other"}}"#
        ));
    }
}
