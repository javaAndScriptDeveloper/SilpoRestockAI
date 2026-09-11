#!/usr/bin/env python3
"""Generates the two committed Grafana dashboards (task 75).

    python3 observability/grafana/build-dashboards.py

Writes komora-business.json and komora-observability.json next to this file. The JSON is what Grafana provisions
(locally through the file provider, in Grafana Cloud through `make dashboard`); this script is what a person edits.
Never hand-edit the JSON — regenerate it, so both Grafanas keep rendering the same file.

Every panel's description names the pitch claim or judging criterion it backs. A panel that cannot name one does
not belong on either dashboard; that is the whole design rule.
"""

import json
from pathlib import Path

HERE = Path(__file__).parent
DS = {"type": "prometheus", "uid": "${DS}"}
ENV = 'env=~"$env"'

# Section colours: a viewer tells the guest section from the Silpo section by colour before reading a title.
GUEST = "blue"
SILPO = "green"

_next_id = [1]


def _id():
    _next_id[0] += 1
    return _next_id[0]


def _target(expr, legend="", ref="A", instant=False):
    target = {"refId": ref, "expr": expr, "legendFormat": legend, "datasource": DS}
    if instant:
        target["instant"] = True
        target["range"] = False
    return target


def _targets(queries, instant=False):
    return [
        _target(expr, legend, ref, instant)
        for (expr, legend), ref in zip(queries, "ABCDEFGH")
    ]


def _base(kind, title, description, x, y, w, h):
    return {
        "id": _id(),
        "type": kind,
        "title": title,
        "description": description,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "datasource": DS,
    }


def row(title, y):
    return {
        "id": _id(),
        "type": "row",
        "title": title,
        "collapsed": False,
        "gridPos": {"x": 0, "y": y, "w": 24, "h": 1},
        "panels": [],
    }


def stat(title, description, queries, x, y, w, h, *, unit=None, color=None, decimals=None, big=False,
         text_mode="value", thresholds=None, max_value=None):
    panel = _base("stat", title, description, x, y, w, h)
    panel["targets"] = _targets(queries)
    defaults = {"color": {"mode": "fixed", "fixedColor": color} if color else {"mode": "thresholds"}}
    if unit:
        defaults["unit"] = unit
    if decimals is not None:
        defaults["decimals"] = decimals
    if max_value is not None:
        defaults["max"] = max_value
    defaults["thresholds"] = thresholds or {"mode": "absolute", "steps": [{"color": color or "green", "value": None}]}
    panel["fieldConfig"] = {"defaults": defaults, "overrides": []}
    panel["options"] = {
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
        "colorMode": "background" if color else "value",
        "graphMode": "none",
        "textMode": text_mode,
        "justifyMode": "center",
        "orientation": "auto",
        "wideLayout": True,
        "text": {"valueSize": 64 if big else None} if big else {},
    }
    return panel


def bargauge(title, description, queries, x, y, w, h, *, unit=None, color=None, max_value=None, decimals=None,
             orientation="horizontal", palette=False):
    panel = _base("bargauge", title, description, x, y, w, h)
    panel["targets"] = _targets(queries)
    defaults = {
        "color": {"mode": "palette-classic"} if palette else {"mode": "fixed", "fixedColor": color or "blue"},
        "min": 0,
        "thresholds": {"mode": "absolute", "steps": [{"color": color or "green", "value": None}]},
    }
    if unit:
        defaults["unit"] = unit
    if max_value is not None:
        defaults["max"] = max_value
    if decimals is not None:
        defaults["decimals"] = decimals
    panel["fieldConfig"] = {"defaults": defaults, "overrides": []}
    panel["options"] = {
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
        "orientation": orientation,
        "displayMode": "gradient",
        "valueMode": "color",
        "showUnfilled": True,
        "namePlacement": "top" if orientation == "horizontal" else "auto",
        "sizing": "auto",
        "minVizWidth": 8,
        "minVizHeight": 16,
        "text": {},
    }
    return panel


def timeseries(title, description, queries, x, y, w, h, *, unit=None, bars=False, stacked=False, fill=None,
               overrides=None, right_axis_refs=(), right_unit=None, max_value=None):
    panel = _base("timeseries", title, description, x, y, w, h)
    panel["targets"] = _targets(queries)
    custom = {
        "drawStyle": "bars" if bars else "line",
        "lineWidth": 2,
        "fillOpacity": fill if fill is not None else (80 if bars else 12),
        "gradientMode": "opacity",
        "showPoints": "never",
        "spanNulls": False,
        "stacking": {"mode": "normal" if stacked else "none", "group": "A"},
        "axisPlacement": "auto",
        "lineInterpolation": "smooth",
    }
    defaults = {"color": {"mode": "palette-classic"}, "custom": custom, "min": 0}
    if unit:
        defaults["unit"] = unit
    if max_value is not None:
        defaults["max"] = max_value
    panel_overrides = list(overrides or [])
    for ref in right_axis_refs:
        panel_overrides.append({
            "matcher": {"id": "byFrameRefID", "options": ref},
            "properties": [
                {"id": "custom.axisPlacement", "value": "right"},
                {"id": "custom.drawStyle", "value": "line"},
                {"id": "custom.fillOpacity", "value": 0},
                {"id": "custom.stacking", "value": {"mode": "none"}},
                {"id": "unit", "value": right_unit or "percent"},
                {"id": "max", "value": 100},
                {"id": "color", "value": {"mode": "fixed", "fixedColor": "red"}},
            ],
        })
    panel["fieldConfig"] = {"defaults": defaults, "overrides": panel_overrides}
    panel["options"] = {
        "legend": {"displayMode": "list", "placement": "bottom", "showLegend": True, "calcs": []},
        "tooltip": {"mode": "multi", "sort": "desc"},
    }
    return panel


def dashboard(uid, title, description, tags, panels, refresh="30s", time_from="now-7d"):
    return {
        "uid": uid,
        "title": title,
        "description": description,
        "tags": tags,
        "editable": True,
        "graphTooltip": 1,
        "timezone": "browser",
        "refresh": refresh,
        "schemaVersion": 39,
        "version": 1,
        "time": {"from": time_from, "to": "now"},
        "timepicker": {"refresh_intervals": ["10s", "30s", "1m", "5m", "15m"]},
        "templating": {
            "list": [
                {
                    "name": "DS",
                    "label": "Datasource",
                    "type": "datasource",
                    "query": "prometheus",
                    "hide": 0,
                    "current": {},
                    "refresh": 1,
                },
                {
                    "name": "env",
                    "label": "Середовище",
                    "type": "query",
                    "datasource": DS,
                    "definition": "label_values(komora_users_registered, env)",
                    "query": {"query": "label_values(komora_users_registered, env)", "refId": "env"},
                    "includeAll": True,
                    "multi": True,
                    "allValue": ".*",
                    "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
                    "refresh": 2,
                    "sort": 1,
                    "hide": 0,
                },
            ]
        },
        "annotations": {"list": []},
        "links": [],
        "panels": panels,
    }


# --------------------------------------------------------------------------------------------------------------
# Business dashboard
# --------------------------------------------------------------------------------------------------------------

def business():
    p = []
    # ---- A · guest value -------------------------------------------------------------------------------------
    p.append(row("🧑‍🍳  A · Цінність для гостя — час і довіра, без жодної гривні", 0))
    p.append(bargauge(
        "Домогосподарства: старт → профіль → перше замовлення",
        "Доказ для критерію «Валідація та масштабування»: скільки людей дійшли від /start до реального замовлення. "
        "Кожна смуга — кількість домогосподарств на цьому етапі, з бази, не з лічильника процесу.",
        [
            (f"max(komora_users_registered{{{ENV}}})", "Написали /start"),
            (f"max(komora_users_onboarded{{{ENV}}})", "Заповнили анкету"),
            (f"max(komora_users_ordered{{{ENV}}})", "Підтвердили замовлення"),
        ],
        0, 1, 8, 6, color=GUEST, decimals=0,
    ))
    p.append(stat(
        "Активних за 7 днів",
        "«Цикл живий, не разова новинка»: домогосподарства, які писали боту за останній тиждень. Абсолютна цифра з "
        "бази, не лічильник процесу — переживає перезапуск.",
        [(f"max(komora_users_active{{{ENV}, window=\"7d\"}})", "")],
        8, 1, 8, 3, color=GUEST, decimals=0,
    ))
    p.append(stat(
        "Онбординг → перше замовлення (медіана)",
        "Selling Points, таблиця #37, рядок 1: «від першого «привіт» до реального замовлення — N хвилин, не днів». "
        "Один раз на домогосподарство; повторювана швидкість — панель праворуч.",
        [(f"max(komora_onboarding_first_order_seconds{{{ENV}, stat=\"median\"}})", "")],
        8, 4, 8, 3, unit="dtdurations", color=GUEST, decimals=0,
    ))
    p.append(stat(
        "Намір → замовлення (медіана)",
        "НОВА метрика (#75) для «мінімум часу на їжу»: від речення в чаті («голова після вчорашнього», «замов усе "
        "для карбонари», «що треба докупити?», «світло вимкнули») до підтвердженого замовлення. Повторювана — одна "
        "цифра на кожне замовлення, — а не одноразова, як онбординг→перше замовлення. Годинник стартує до виклику "
        "класифікатора, тож «думання» агента входить у число.",
        [(f"max(komora_intent_order_median_seconds{{{ENV}, intent=\"ALL\"}})", "")],
        16, 1, 8, 6, unit="dtdurations", color=GUEST, decimals=0, big=True,
    ))
    p.append(bargauge(
        "Намір → замовлення за типом наміру (медіана)",
        "Той самий годинник, розкладений за наміром із #31: «гастрит, похмілля, п'ятничні знижки — жоден із цих "
        "сценаріїв не проєктувався окремо. Один агент, один роутер намірів». AD_HOC_SCHEDULED_PURCHASE рахується "
        "від свіпу, що запустив покупку, а не від речення «до п'ятниці» — дедлайн обирає людина.",
        [(f"max by (intent) (komora_intent_order_median_seconds{{{ENV}, intent!=\"ALL\"}})", "{{intent}}")],
        0, 7, 8, 6, unit="dtdurations", color=GUEST, decimals=0,
    ))
    p.append(stat(
        "Дозамовлень підтверджено без правок",
        "Точність = довіра: «K з N дозамовлень підтверджено як запропоновано — агент вчиться» (Selling Points, "
        "рядок 3). Частка дозамовлень, які людина підтвердила, не змінивши жодного рядка.",
        [(f"100 * sum(komora_reorders{{{ENV}, edited=\"false\"}}) / sum(komora_reorders{{{ENV}}})", "")],
        8, 7, 4, 6, unit="percent", color=GUEST, decimals=0, max_value=100,
    ))
    p.append(stat(
        "Найдовша серія без правок",
        "Друге число за історією точності: найдовший ланцюжок поспіль підтверджених без правок замовлень у одного "
        "домогосподарства (trust_level.consecutive_unedited_confirmations).",
        [(f"max(komora_trust_streak{{{ENV}, stat=\"max\"}})", "")],
        12, 7, 4, 6, color=GUEST, decimals=0,
    ))
    p.append(stat(
        "Чек-іни: відповіли",
        "Залученість: «на K з N запитів «що лишилось» люди відповіли — цикл живий» (Selling Points, рядок 2). "
        "Чесна примітка: на тестовому 2-хвилинному інтервалі запитів багато, відповідей — скільки є; для пітчу "
        "цитуй вибірку поруч, не лише відсоток.",
        [(f"100 * sum(komora_checkins{{{ENV}, stat=\"answered\"}}) / sum(komora_checkins{{{ENV}, stat=\"prompted\"}})", "")],
        16, 7, 4, 3, unit="percent", color=GUEST, decimals=0, max_value=100,
    ))
    p.append(stat(
        "Чек-іни: відповідей / запитів",
        "Вибірка за відсотком ліворуч — «K з N», бо «75 %» без «з чого» не доказ.",
        [
            (f"sum(komora_checkins{{{ENV}, stat=\"answered\"}})", "відповідей"),
            (f"sum(komora_checkins{{{ENV}, stat=\"prompted\"}})", "запитів"),
        ],
        16, 10, 4, 3, color=GUEST, decimals=0, text_mode="value_and_name",
    ))
    p.append(stat(
        "Позицій списку знайдено в каталозі",
        "«94 % рядків списку стають реальним SKU, а решта — чесне «не знайшов», не чужий товар» (Selling Points, "
        "рядок 4). Частка рядків усіх зібраних кошиків, для яких «Сільпо» знайшло товар.",
        [(f"100 * sum(komora_orders_lines{{{ENV}, result=\"resolved\"}}) / sum(komora_orders_lines{{{ENV}}})", "")],
        20, 7, 4, 6, unit="percent", color=GUEST, decimals=0, max_value=100,
    ))

    # ---- A2 · the social primitive (task 80) ----------------------------------------------------------------
    p.append(row("🤝  A2 · Люди, яких бот привів сам — групові збори і подарунки", 13))
    p.append(stat(
        "Групових зборів → замовлено",
        "Примітив 4 «людина → агент → людина» (#68): раунди в груповому чаті, які дійшли до підтвердженого "
        "замовлення організатора. Кожен такий раунд — компанія, яка побачила агента в роботі.",
        [(f"sum(komora_group_rounds{{{ENV}, status=\"ORDERED\"}})", "")],
        0, 14, 6, 5, color=GUEST, decimals=0, big=True,
    ))
    p.append(stat(
        "Групових зборів усього",
        "Усі раунди в усіх станах — відкриті, з пропозицією на столі, погоджені, замовлені.",
        [(f"sum(komora_group_rounds{{{ENV}}})", "")],
        6, 14, 4, 5, color=GUEST, decimals=0,
    ))
    p.append(stat(
        "Людей відповіли у зборах",
        "Вірусна нотка з пітчу: кожна людина, чию відповідь порахували в раунді, щойно побачила бота в роботі — "
        "органічний канал залучення, вбудований у продукт, а не в рекламу.",
        [(f"sum(komora_group_participants{{{ENV}}})", "")],
        10, 14, 5, 5, color=GUEST, decimals=0, big=True,
    ))
    p.append(stat(
        "Подарунків підтверджено",
        "Той самий примітив (#81): замовлення, доставлене на адресу друга. Відправник адреси не бачить; "
        "отримувач бачить бота — ще один канал росту.",
        [(f"sum(komora_gift_orders{{{ENV}, status=\"CONFIRMED\"}})", "")],
        15, 14, 4, 5, color=GUEST, decimals=0, big=True,
    ))
    p.append(bargauge(
        "Подарунки за станом",
        "AWAITING_ADDRESS — у друга спитали адресу; RESOLVED — адреса є; CART_PRESENTED — кошик перед "
        "відправником; CONFIRMED — підтверджено; UNREACHABLE — друг ще не писав боту, відправника попросили "
        "назвати адресу; EXPIRED — ніхто не відповів за добу.",
        [(f"sum by (status) (komora_gift_orders{{{ENV}}})", "{{status}}")],
        19, 14, 5, 5, color=GUEST, decimals=0,
    ))

    # ---- B · Silpo value -------------------------------------------------------------------------------------
    p.append(row("💰  B · Цінність і revenue для «Сільпо» — гроші", 20))
    p.append(stat(
        "GMV — підтверджені замовлення",
        "Критерій «Цінність для бізнесу», крок 13.7 сценарію: «підтвердити замовлення в Telegram — і через 30 с "
        "GMV на дашборді змінюється». Сума збережених total підтверджених замовлень (доставка включно). Чесна межа: "
        "замовлення без збереженої суми не входять — їх кількість праворуч.",
        [(f"sum(komora_orders_gmv_uah{{{ENV}}})", "")],
        0, 21, 8, 6, unit="currencyUAH", color=SILPO, decimals=0, big=True,
    ))
    p.append(stat(
        "Середній чек",
        "GMV / кількість підтверджених замовлень зі збереженою сумою. Selling Points, розділ «Grafana-дашборд»: "
        "«гості/GMV/середній чек наживо».",
        [(f"sum(komora_orders_gmv_uah{{{ENV}}}) / sum(komora_orders_confirmed{{{ENV}}})", "")],
        8, 21, 4, 6, unit="currencyUAH", color=SILPO, decimals=0,
    ))
    p.append(stat(
        "Підтверджених замовлень",
        "«Чи це реальні гроші»: кількість підтверджених замовлень у базі, усіх типів.",
        [(f"sum(komora_orders_confirmed{{{ENV}}})", "")],
        12, 21, 4, 6, color=SILPO, decimals=0,
    ))
    p.append(stat(
        "Знижок «Сільпо» у кошиках",
        "«Економія — цифра самого «Сільпо»»: сума subDiscount підтверджених кошиків — перевага акційних позицій "
        "при виборі, не власний каталог знижок.",
        [(f"sum(komora_orders_savings_uah{{{ENV}}})", "")],
        16, 21, 4, 6, unit="currencyUAH", color=SILPO, decimals=0,
    ))
    p.append(stat(
        "Замовлень без збереженої суми",
        "Покриття GMV: підтверджені замовлення, у яких сума не збережена (до задачі #54). Має бути 0 на свіжому "
        "прогоні; якщо ні — GMV занижений рівно на них, і ця панель каже про це вголос.",
        [(f"sum(komora_orders_value_missing{{{ENV}}})", "")],
        20, 21, 4, 6, color=SILPO, decimals=0,
    ))
    p.append(timeseries(
        "Підтверджено замовлень за типом, за годину",
        "Кадр «воно рухається»: перше замовлення (INITIAL), дозамовлення (SCHEDULED_REORDER) і разові (AD_HOC) — "
        "стовпчик на годину, коли їх підтвердили. Це лічильник процесу: після перезапуску починає з нуля.",
        [(f"sum by (type) (increase(komora_orders_confirmations_total{{{ENV}}}[1h]))", "{{type}}")],
        0, 27, 24, 6, bars=True, stacked=True,
    ))

    # ---- B · featuring ---------------------------------------------------------------------------------------
    p.append(row("💰  B · Фічеринг: Featured Share Rate і Attributed Revenue — скільки грошей робить розміщення", 33))
    p.append(stat(
        "Attributed Revenue — усі розміщення",
        "Головна цифра монетизації (#63/#64): реальні гривні рядків саме промотованого товару в підтверджених "
        "замовленнях (ціна × кількість), не вартість усього кошика і не оцінка. Те, що retail media називає "
        "Attributed Sales. Платні розміщення і власні марки разом; нижче — окремо.",
        [(f"max(komora_promotion_revenue_overall_uah{{{ENV}, type=\"ALL\"}})", "")],
        0, 34, 8, 7, unit="currencyUAH", color=SILPO, decimals=0, big=True,
    ))
    p.append(stat(
        "Featured Share Rate — усі розміщення",
        "Аналог Share of Shelf у retail media: частка розв'язань категорії, яку виграв промотований товар, від усіх "
        "розв'язань цієї категорії (включно з рядками, де розміщення не мало права виграти через обмеження "
        "господарства — тобто чесна, занижена частка). «Власна марка «Сільпо» отримує 94 % категорії молока — і "
        "це порахована частка, не покази».",
        [(f"100 * max(komora_promotion_share_overall{{{ENV}, type=\"ALL\"}})", "")],
        8, 34, 6, 7, unit="percent", color=SILPO, decimals=0, max_value=100,
    ))
    p.append(bargauge(
        "Attributed Revenue за пулом",
        "PAID_PARTNER — зовнішній партнер платить за частку категорії; OWN_BRAND_MARGIN_BOOST — той самий двигун "
        "для власних high-margin брендів «Сільпо», без зовнішнього партнера. Два бізнеси, ніколи не одна змішана "
        "цифра.",
        [(f"max by (type) (komora_promotion_revenue_overall_uah{{{ENV}, type!=\"ALL\"}})", "{{type}}")],
        14, 34, 5, 7, unit="currencyUAH", color=SILPO, decimals=0,
    ))
    p.append(bargauge(
        "Featured Share Rate за пулом",
        "Частка категорій окремо для платних розміщень і для власних марок.",
        [(f"100 * max by (type) (komora_promotion_share_overall{{{ENV}, type!=\"ALL\"}})", "{{type}}")],
        19, 34, 5, 7, unit="percent", color=SILPO, decimals=0, max_value=100,
    ))
    for x, pool, owner in ((0, "PAID_PARTNER", "партнер"), (12, "OWN_BRAND_MARGIN_BOOST", "бренд")):
        p.append(bargauge(
            f"{pool} · Featured Share Rate і lift за брендом",
            f"Кожен {owner}: його частка категорії (FSR) і lift — на скільки пунктів частка вища за органічний "
            "базлайн (виміряний, коли органічних розв'язань ≥ 5, інакше апроксимований як 1/кандидатів; метод "
            "підписаний у komora_promotion_baseline). Немає базлайну — немає смуги lift, а не нуль.",
            [
                (f"100 * max by (partner, category) (komora_promotion_share{{{ENV}, type=\"{pool}\"}})",
                 "FSR · {{partner}} / {{category}}"),
                (f"100 * max by (partner, category) (komora_promotion_lift{{{ENV}, type=\"{pool}\"}})",
                 "lift, п.п. · {{partner}} / {{category}}"),
            ],
            x, 41, 12, 7, unit="percent", color=SILPO, decimals=0, max_value=100,
        ))
        p.append(bargauge(
            f"{pool} · Attributed Revenue за брендом",
            "Гривні рядків саме цього товару в підтверджених замовленнях. Рядок без збереженої ціни не рахується "
            "як нуль — він порахований окремо у звіті make promotions.",
            [(f"max by (partner, product) (komora_promotion_revenue_uah{{{ENV}, type=\"{pool}\"}})",
              "{{partner}} · {{product}}")],
            x, 48, 12, 5, unit="currencyUAH", color=SILPO, decimals=0,
        ))
        events = f"komora_promotion_events{{{ENV}, type=\"{pool}\""
        p.append(bargauge(
            f"{pool} · Conversion Rate між стадіями",
            "Conversion Rate — універсальний термін воронки: показ → у кошику, у кошику → підтверджене замовлення, "
            "по кожному бренду. Воронка не суворо вкладена: підтверджене замовлення записується для кожного активного "
            "розміщення, товар якого був у замовленні, навіть якщо той кошик не дав події «у кошику» (дозамовлення, "
            "минуле замовлення) — тому «кошик → замовлення» може перевищити 100 %. Смуга впирається в 100, число "
            "поруч справжнє.",
            [
                (f"100 * sum by (partner) ({events}, event=\"ADDED_TO_CART\"}}) / "
                 f"sum by (partner) ({events}, event=\"IMPRESSION\"}})", "показ → кошик · {{partner}}"),
                (f"100 * sum by (partner) ({events}, event=\"CONFIRMED_ORDER\"}}) / "
                 f"sum by (partner) ({events}, event=\"ADDED_TO_CART\"}})", "кошик → замовлення · {{partner}}"),
            ],
            x, 53, 12, 6, unit="percent", color=SILPO, decimals=0, max_value=100,
        ))
    return dashboard(
        "komora-business",
        "Комора — Business",
        "Дві секції: A — цінність для гостя (сині плитки, без грошей), B — цінність і revenue для «Сільпо» "
        "(зелені плитки). Кожна панель у своєму описі називає заяву з «Selling Points» або критерій журі, "
        "який вона доводить.",
        ["komora", "business", "pitch"],
        p,
    )


# --------------------------------------------------------------------------------------------------------------
# Technical dashboard
# --------------------------------------------------------------------------------------------------------------

def technical():
    mcp = f"komora_mcp_call_seconds_count{{{ENV}}}"
    mcp_ok = f"komora_mcp_call_seconds_count{{{ENV}, outcome=\"success\"}}"
    mcp_bad = f"komora_mcp_call_seconds_count{{{ENV}, outcome!=\"success\"}}"
    mcp_bucket = f"komora_mcp_call_seconds_bucket{{{ENV}}}"
    claude = f"komora_claude_call_seconds_count{{{ENV}}}"
    claude_ok = f"komora_claude_call_seconds_count{{{ENV}, outcome=\"success\"}}"
    claude_bucket = f"komora_claude_call_seconds_bucket{{{ENV}}}"
    p = []
    p.append(row("🔧  MCP «Сільпо» — RED: Rate · Errors · Duration по кожному інструменту", 0))
    p.append(stat(
        "MCP-викликів за хвилину",
        "Rate. Критерій «Агентність»: «живі MCP tool-calls на екрані» — це той самий потік, що й консоль з #58, "
        "але як число. Середнє за останні 2 хвилини.",
        [(f"sum(rate({mcp}[2m])) * 60", "")],
        0, 1, 6, 4, decimals=1, color="purple",
    ))
    p.append(stat(
        "MCP: успішних викликів",
        "Errors, навпаки: частка викликів до «Сільпо» з outcome=success за 5 хвилин. Один зелений ряд у консолі "
        "= один success тут.",
        [(f"100 * sum(rate({mcp_ok}[5m])) / sum(rate({mcp}[5m]))", "")],
        6, 1, 6, 4, unit="percent", decimals=1, max_value=100,
        thresholds={"mode": "absolute", "steps": [{"color": "red", "value": None}, {"color": "orange", "value": 90},
                                                   {"color": "green", "value": 98}]},
    ))
    p.append(stat(
        "MCP: p95 тривалості",
        "Duration: 95-й перцентиль тривалості виклику до «Сільпо» за 5 хвилин, по всіх інструментах. Інтерпольовано "
        "між SLO-межами (100 мс … 30 с) з application.yml.",
        [(f"histogram_quantile(0.95, sum by (le) (rate({mcp_bucket}[5m])))", "")],
        12, 1, 6, 4, unit="s", decimals=2,
        thresholds={"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "orange", "value": 2},
                                                   {"color": "red", "value": 10}]},
    ))
    p.append(stat(
        "Різних інструментів за період",
        "Критерій «Якість використання MCP»: «ми реально використовуємо N із 40 інструментів — не два-три для "
        "галочки». Інструменти silpo_*, які викликались хоча б раз у вибраному вікні. Статичний список — артефакт #55.",
        [(f"count(count by (tool) (increase({mcp}[$__range]) > 0))", "")],
        18, 1, 6, 4, decimals=0, color="purple",
    ))
    p.append(timeseries(
        "Rate: викликів за хвилину, за інструментом",
        "Кадр для камери: кожен колір — інструмент silpo_*, висота — викликів за хвилину. Збірка кошика читається "
        "як хвиля get_cart → time_slots → find_products_batch → add_or_update.",
        [(f"sum by (tool) (rate({mcp}[2m])) * 60", "{{tool}}")],
        0, 5, 16, 8, bars=True, stacked=True,
    ))
    p.append(bargauge(
        "Викликів за інструментом за період",
        "Лідерборд інструментів: скільки разів кожен silpo_* викликано у вибраному вікні. Довгий хвіст — це і є "
        "«широке покриття інструментів», не два-три для галочки.",
        [(f"sum by (tool) (increase({mcp}[$__range]))", "{{tool}}")],
        16, 5, 8, 16, decimals=0, palette=True,
    ))
    p.append(timeseries(
        "Errors: невдалих викликів за хвилину, за інструментом і причиною",
        "Стовпчики — невдалі виклики (outcome ≠ success) за інструментом; червона лінія — загальний відсоток "
        "помилок за 5 хвилин. Реальний збій «Сільпо» 03:26–03:28 у сесії 16 виглядав тут як один стовпчик, а бот "
        "у той момент чесно сказав «Спробуй ще раз за хвилину».",
        [
            (f"sum by (tool, outcome) (rate({mcp_bad}[5m])) * 60", "{{tool}} · {{outcome}}"),
            (f"100 * sum(rate({mcp_bad}[5m])) / sum(rate({mcp}[5m]))", "помилок, % (усі інструменти)"),
        ],
        0, 13, 8, 8, bars=True, stacked=True, right_axis_refs=("B",),
    ))
    p.append(timeseries(
        "Duration: p50 / p95 за інструментом",
        "Тривалість виклику до «Сільпо»: медіана (тонка) і p95 (товста) по кожному інструменту, за 5 хвилин. "
        "find_products_batch — найдовший; його p95 — це і є «скільки чекає гість на кошик».",
        [
            (f"histogram_quantile(0.50, sum by (le, tool) (rate({mcp_bucket}[5m])))", "p50 · {{tool}}"),
            (f"histogram_quantile(0.95, sum by (le, tool) (rate({mcp_bucket}[5m])))", "p95 · {{tool}}"),
        ],
        8, 13, 8, 8, unit="s",
        overrides=[{"matcher": {"id": "byFrameRefID", "options": "A"},
                    "properties": [{"id": "custom.lineWidth", "value": 1},
                                   {"id": "custom.lineStyle", "value": {"fill": "dash", "dash": [4, 4]}}]}],
    ))

    p.append(row("🧠  Claude — RED: «спершу думає, потім діє»", 21))
    p.append(timeseries(
        "Rate: викликів Claude за хвилину, за призначенням",
        "Кожен 🧠-рядок консолі (#58) — один виклик тут: класифікація наміру, план, матчер товарів, чек-ін.",
        [(f"sum by (call) (rate({claude}[2m])) * 60", "{{call}}")],
        0, 22, 8, 7, bars=True, stacked=True,
    ))
    p.append(timeseries(
        "Claude: успішних викликів, %",
        "Частка викликів моделі з outcome=success за 5 хвилин. Невдача тут — «Не вдалось» гостю з кнопкою "
        "«Спробувати ще раз», не мовчазний кошик.",
        [(f"100 * sum(rate({claude_ok}[5m])) / sum(rate({claude}[5m]))", "успішних, %")],
        8, 22, 8, 7, unit="percent", max_value=100,
    ))
    p.append(timeseries(
        "Claude: p95 тривалості за призначенням і моделлю",
        "Duration: 95-й перцентиль тривалості виклику. Матчер на швидкій моделі — секунди; план — десятки секунд.",
        [(f"histogram_quantile(0.95, sum by (le, call, model) (rate({claude_bucket}[5m])))",
          "{{call}} · {{model}}")],
        16, 22, 8, 7, unit="s",
    ))

    p.append(row("🤖  Агент — намір, кошик, чесна помилка", 29))
    p.append(timeseries(
        "Намірів за годину: розпізнано / не розпізнано / збій класифікатора",
        "«Кожна нова фіча — новий обробник намірів у роутері» (#31). routed — речення дійшло до обробника; "
        "unclassified — уточнювальне питання; failed — класифікатор упав. Який саме намір — артефакт #55.",
        [(f"sum by (outcome) (increase(komora_intent_classified_total{{{ENV}}}[1h]))", "{{outcome}}")],
        0, 30, 6, 7, bars=True, stacked=True,
    ))
    p.append(timeseries(
        "Збірка кошика: результат за годину і p95",
        "«Невдалий вибір товару = чесна помилка, не кошик». Стовпчики — збірки за результатом; лінія — p95 "
        "тривалості збірки (усі виклики до «Сільпо» й матчер разом).",
        [
            (f"sum by (outcome) (increase(komora_cart_build_seconds_count{{{ENV}}}[1h]))", "{{outcome}}"),
            (f"histogram_quantile(0.95, sum by (le) (rate(komora_cart_build_seconds_bucket{{{ENV}}}[10m])))",
             "p95, с"),
        ],
        6, 30, 6, 7, bars=True, stacked=True, right_axis_refs=("B",), right_unit="s",
    ))
    p.append(timeseries(
        "Повідомлень про збій, показаних людям, за годину",
        "Має триматись біля нуля; сплеск — реальний сигнал. Джерело × вид: cart_build/no_address, "
        "cart_build/empty_cart, recovery/silpo…",
        [(f"sum by (source, kind) (increase(komora_failure_message_total{{{ENV}}}[1h]))", "{{source}} · {{kind}}")],
        12, 30, 6, 7, bars=True, stacked=True,
    ))
    p.append(timeseries(
        "Намір → замовлення: p95 за годину, за наміром",
        "Технічний бік нової метрики #75 (медіана — на Business-дашборді): 95-й перцентиль часу від речення до "
        "підтвердження за останню годину, з таймера на місці підтвердження.",
        [(f"histogram_quantile(0.95, sum by (le, intent) (rate(komora_intent_order_seconds_bucket{{{ENV}}}[1h])))",
          "{{intent}}")],
        18, 30, 6, 7, unit="s",
    ))

    p.append(row("⚙️  Застосунок — здоров'я процесу", 37))
    http = f"http_server_requests_seconds_count{{{ENV}}}"
    p.append(timeseries(
        "HTTP: запитів/с і частка 5xx",
        "Вебхуки Telegram, OAuth-колбеки, WebApp-форма й /actuator. Червона лінія — відсоток відповідей 5xx.",
        [
            (f"sum(rate({http}[5m]))", "запитів/с"),
            (f"100 * sum(rate(http_server_requests_seconds_count{{{ENV}, status=~\"5..\"}}[5m])) / sum(rate({http}[5m]))",
             "5xx, %"),
        ],
        0, 38, 8, 6, right_axis_refs=("B",),
    ))
    p.append(timeseries(
        "HTTP: середня і максимальна тривалість",
        "Середня тривалість запиту за 5 хвилин і максимум за скрейп. Довгий вебхук — це кошик, що збирається "
        "синхронно всередині нього.",
        [
            (f"sum(rate(http_server_requests_seconds_sum{{{ENV}}}[5m])) / sum(rate({http}[5m]))", "середня"),
            (f"max(http_server_requests_seconds_max{{{ENV}}})", "макс."),
        ],
        8, 38, 8, 6, unit="s",
    ))
    p.append(timeseries(
        "JVM: використано heap",
        "Пам'ять процесу. Один інстанс, один контейнер — має бути рівною лінією.",
        [(f"sum(jvm_memory_used_bytes{{{ENV}, area=\"heap\"}})", "heap")],
        16, 38, 8, 6, unit="bytes",
    ))
    p.append(stat("Uptime", "Скільки працює процес без перезапуску.",
                  [(f"max(process_uptime_seconds{{{ENV}}})", "")], 0, 44, 6, 4, unit="dtdurations", decimals=0))
    p.append(stat("CPU системи", "Завантаження CPU хоста, як бачить JVM.",
                  [(f"max(system_cpu_usage{{{ENV}}}) * 100", "")], 6, 44, 6, 4, unit="percent", decimals=0,
                  max_value=100))
    p.append(stat("Активних з'єднань з БД", "Hikari: з'єднання в роботі. Пул — 10; чек-іновий свіп і вебхуки ділять його.",
                  [(f"sum(hikaricp_connections_active{{{ENV}}})", "")], 12, 44, 6, 4, decimals=0))
    p.append(stat(
        "Відкритих circuit breaker-ів",
        "Resilience4j: 0 — усе закрито (нормально); 1+ — якийсь клієнт (Claude, STT, Google) відрізаний після серії "
        "збоїв і відновиться сам.",
        [(f"sum(resilience4j_circuitbreaker_state{{{ENV}, state=\"open\"}})", "")],
        18, 44, 6, 4, decimals=0,
        thresholds={"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "red", "value": 1}]},
    ))
    return dashboard(
        "komora-observability",
        "Комора — Technical",
        "MCP «Сільпо» як RED-метрики по кожному інструменту (rate / errors / duration), Claude в тій самій рамці, "
        "далі надійність агента і здоров'я процесу. Візуальний бік консолі з #58.",
        ["komora", "technical", "mcp"],
        p,
        refresh="10s",
        time_from="now-3h",
    )


def main():
    for name, build in (("komora-business.json", business), ("komora-observability.json", technical)):
        _next_id[0] = 1
        path = HERE / name
        path.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
