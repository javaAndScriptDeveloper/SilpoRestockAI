const tg = window.Telegram.WebApp;
tg.expand();

const CHILD_BRACKETS = [
  ["AGE_0_3", "0–3"],
  ["AGE_4_7", "4–7"],
  ["AGE_8_12", "8–12"],
  ["AGE_13_17", "13–17"],
];

function addChildRow(bracket) {
  const row = document.createElement("div");
  row.className = "child-row";
  const select = document.createElement("select");
  select.className = "child-bracket";
  for (const [value, label] of CHILD_BRACKETS) {
    const opt = document.createElement("option");
    opt.value = value;
    opt.textContent = label;
    if (value === bracket) opt.selected = true;
    select.appendChild(opt);
  }
  const remove = document.createElement("button");
  remove.type = "button";
  remove.textContent = "✕";
  remove.onclick = () => row.remove();
  row.appendChild(select);
  row.appendChild(remove);
  document.getElementById("children").appendChild(row);
}

document.getElementById("addChild").onclick = () => addChildRow("AGE_4_7");

function applyPrefill() {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get("prefill");
  if (!raw) return;
  try {
    const prefill = JSON.parse(atob(raw.replace(/-/g, "+").replace(/_/g, "/")));
    if (prefill.householdSize) {
      document.getElementById("adultMale").value = Math.ceil(prefill.householdSize / 2);
      document.getElementById("adultFemale").value = Math.floor(prefill.householdSize / 2);
    }
  } catch (e) {
    // Malformed or absent prefill is not fatal — the form just starts blank.
    console.warn("could not apply onboarding prefill", e);
  }
}
applyPrefill();

document.getElementById("onboarding-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const restrictions = Array.from(document.querySelectorAll('input[name="restriction"]:checked')).map(
    (el) => el.value,
  );
  const childrenAgeBrackets = Array.from(document.querySelectorAll(".child-bracket")).map((el) => el.value);
  const payload = {
    adultMale: parseInt(document.getElementById("adultMale").value, 10) || 0,
    adultFemale: parseInt(document.getElementById("adultFemale").value, 10) || 0,
    childrenAgeBrackets,
    restrictions,
    restrictionsOther: document.getElementById("restrictionsOther").value.trim(),
    dietType: document.getElementById("dietType").value,
    cookingTimePreference: document.querySelector('input[name="cookingTime"]:checked').value,
  };
  tg.sendData(JSON.stringify(payload));
});
