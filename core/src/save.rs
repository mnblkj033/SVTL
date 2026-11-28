//! 存档只读解析：从存档 XML 里取玩家数据、已触发事件、邮件。
//!
//! 只读，不写回。存档格式是 XmlSerializer 产出的 XML，
//! 这里用极简标签扫描，不引第三方解析器。
use crate::host::log_line;

#[derive(Default, Debug, Clone, PartialEq)]
pub struct SaveSummary {
    pub name: String,
    pub money: i32,
    pub day: i32,
    pub season: i32,
    pub year: i32,
    pub events_seen: Vec<String>,
    pub mail: Vec<String>,
    pub spouse: String,
    pub farm_name: String,
}

fn find_tag<'a>(xml: &'a str, tag: &str) -> Option<&'a str> {
    let open = format!("<{tag}>");
    let close = format!("</{tag}>");
    let start = xml.find(&open)? + open.len();
    let end = xml[start..].find(&close)? + start;
    Some(&xml[start..end])
}

fn text_of(xml: &str, tag: &str) -> String {
    find_tag(xml, tag)
        .map(|s| unescape(s.trim()))
        .unwrap_or_default()
}

fn int_of(xml: &str, tag: &str) -> i32 {
    find_tag(xml, tag)
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(0)
}

fn list_of(xml: &str, tag: &str) -> Vec<String> {
    let block = match find_tag(xml, tag) {
        Some(b) => b,
        None => return Vec::new(),
    };
    let mut out = Vec::new();
    let mut rest = block;
    while let Some(p) = rest.find("<string>") {
        let start = p + "<string>".len();
        let end = match rest[start..].find("</string>") {
            Some(e) => start + e,
            None => break,
        };
        out.push(unescape(&rest[start..end]));
        rest = &rest[end + "</string>".len()..];
    }
    out
}

fn unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/// 解析存档文本。
pub fn parse(xml: &str) -> SaveSummary {
    let player = find_tag(xml, "player").unwrap_or(xml);
    SaveSummary {
        name: text_of(player, "name"),
        money: int_of(player, "money"),
        day: int_of(xml, "dayOfMonth"),
        season: int_of(xml, "currentSeason"),
        year: int_of(xml, "year"),
        events_seen: list_of(player, "eventsSeen"),
        mail: list_of(player, "mailReceived"),
        spouse: text_of(player, "spouse"),
        farm_name: text_of(xml, "farmName"),
    }
}

/// 读一个存档文件并派发 SAVE_LOADED。返回是否成功。
pub fn read_and_dispatch(path: &str) -> bool {
    let text = match std::fs::read_to_string(path) {
        Ok(t) => t,
        Err(e) => {
            log_line(&format!("[save] 读不了 {path}: {e}"));
            return false;
        }
    };
    let s = parse(&text);
    log_line(&format!(
        "[save] {} 第{}年 第{}天 金钱{} 事件{}条 邮件{}条",
        s.name,
        s.year,
        s.day,
        s.money,
        s.events_seen.len(),
        s.mail.len()
    ));
    crate::host::dispatch(
        crate::api::EV_SAVE_LOADED,
        &format!(
            "{{\"name\":\"{}\",\"money\":{},\"day\":{},\"season\":{},\"year\":{},\"spouse\":\"{}\",\"farm\":\"{}\",\"events\":{},\"mail\":{}}}",
            s.name,
            s.money,
            s.day,
            s.season,
            s.year,
            s.spouse,
            s.farm_name,
            s.events_seen.len(),
            s.mail.len()
        ),
    );
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"<?xml version="1.0"?>
<SaveGame>
  <player>
    <name>阿比</name>
    <money>12345</money>
    <spouse>Abigail</spouse>
    <eventsSeen>
      <string>60367</string>
      <string>1002</string>
    </eventsSeen>
    <mailReceived>
      <string>welcome</string>
    </mailReceived>
  </player>
  <dayOfMonth>14</dayOfMonth>
  <currentSeason>2</currentSeason>
  <year>3</year>
  <farmName>枫叶农场</farmName>
</SaveGame>"#;

    #[test]
    fn parses_player_and_events() {
        let s = parse(SAMPLE);
        assert_eq!(s.name, "阿比");
        assert_eq!(s.money, 12345);
        assert_eq!(s.day, 14);
        assert_eq!(s.season, 2);
        assert_eq!(s.year, 3);
        assert_eq!(s.spouse, "Abigail");
        assert_eq!(s.farm_name, "枫叶农场");
        assert_eq!(s.events_seen, vec!["60367", "1002"]);
        assert_eq!(s.mail, vec!["welcome"]);
    }
}
