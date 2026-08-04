(() => {
  const out = document.getElementById("out");
  const btn = document.getElementById("btn-ping");
  const raBase = (window.HELLO_RA_BASE || "http://127.0.0.1:8081").replace(/\/$/, "");

  btn.addEventListener("click", async () => {
    out.textContent = "Requesting…";
    try {
      const res = await fetch(`${raBase}/api/ussd/begin`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ msisdn: "84901234567", ussdString: "*101#" }),
      });
      const text = await res.text();
      out.textContent = `${res.status} ${res.statusText}\n\n${text}`;
    } catch (err) {
      out.textContent = String(err);
    }
  });
})();
