# CareNexus: Zimbabwe and South Africa regulatory briefing

Prepared 12 November 2026 for the CareNexus team. Scope: data protection, privacy, health-sector rules (telehealth,
prescriptions, records, medical devices), consumer and online terms, payments, tax and currency, cross-border
operation, sub-processors, records retention and AI risk alerts, for a launch in Zimbabwe (ZW) and South Africa (SA).

## 1. Read this first

This is a researched briefing, not legal advice. It was compiled from the statutes, regulations, regulator guidance and
professional-council guidelines linked inline, plus some law-firm and news summaries (marked as such). Each research
item was fact-checked against a source; where the fact-check corrected the original claim, the corrected version is
used here. Laws and regulator practice change, and several points have no settled answer. **Before launch, have a
Zimbabwean lawyer and a South African lawyer confirm every item marked "before launch", have a tax adviser confirm the
VAT points, and answer the questions in section 8.**

Conventions:

- Every legal statement links to the source that was checked. "(unverified)" means no primary or reliable secondary
  source confirmed the statement; it is used sparingly and only where that is still true.
- Zimbabwean fines are stated as "levels" on the standard scale. Under SI 14A of 2023, level 7 = US$400, level 8 =
  US$500, level 11 = US$1,000 and level 13 = US$3,000. These amounts are confirmed by secondary sources (Pindula, ZIMRA,
  Herald), not by the gazetted notice. The dollar values of levels 5, 6, 10 and 12 were not checked (unverified).
- South African POPIA fines: most POPIA breaches are an "interference" (s 73), not an offence. The route to a fine is a
  complaint, then an enforcement notice (s 95); ignoring the notice is an offence (s 103(1)) punishable by a fine or up
  to 10 years (s 107(a)), or by an administrative fine of up to R10 million (s 109). Data subjects can also claim
  damages without proving fault (s 99). A few acts are direct offences (for example s 59, s 105, s 106)
  ([POPIA](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf);
  [popia.co.za s 109](https://popia.co.za/section-109-administrative-fines/)).
- "Before launch / soon after / later" is our suggested priority, not a legal deadline.

## 2. Summary: what matters most before launch

1. **Licences and named officers.** Zimbabwe needs a POTRAZ data controller licence before any processing (Form DP1;
   Tier 2, 1,001 to 100,000 people, is US$30 application plus US$300 a year; renew 3 months before the 12-month expiry;
   up to level 11 or 7 years without one) and a POTRAZ-certified DPO on Form DP2
   ([SI 155 of 2024](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf)).
   POTRAZ began inspecting licences and DPO appointments on 1 September 2026, with health care providers fourth in
   priority ([Veritas Bill Watch 27/2026](https://www.veritaszim.net/node/8052)). South Africa needs an Information
   Officer registered on the Regulator's eServices portal before they can act
   ([POPIA s 55(2)](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf);
   [Information Regulator](https://inforegulator.org.za/popia/)).
2. **Decide what CareNexus legally is in each country**: data controller or processor; health institution or technology
   provider; which company contracts with users; and who is the merchant of record for consultation fees. Licensing,
   consent, payment-intermediation, VAT and HPCSA fee-sharing answers all depend on it.
3. **Health data needs explicit, recorded, withdrawable consent, separate from the terms.** Zimbabwe requires written
   consent for "sensitive data", which there includes health, age, gender, family status and financial history
   ([CDPA ss 3, 11, 12](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)).
   In South Africa a platform that is not itself a healthcare provider probably cannot rely on the s 32(1)(a)
   exemption and should get s 27 consent; disclosure of health information to caregivers or insurers also needs the
   patient's written consent under the National Health Act
   ([POPIA ss 26, 27, 32](https://popia.co.za/section-32-authorisation-concerning-data-subjects-health-or-sex-life/);
   [NHA s 14](https://www.acts.co.za/national-health/14__confidentiality)). Today CareNexus records acceptance of the
   terms and the privacy notice only.
4. **Hosting outside both countries is a regulated transfer in both.** Zimbabwe: notify POTRAZ, submit a DPIA, sign
   data-sharing agreements and get express consent naming the destination
   ([CDPG 5 of 2024](https://www.veritaszim.net/sites/veritas_d/files/05%20Guideline%20on%20cross%20border%20transfer.pdf)).
   South Africa: health or children's data sent to a country without adequate protection needs the Regulator's prior
   authorisation, and storage counts
   ([Prior Authorisation Guidance Note, Jan 2024](https://inforegulator.org.za/wp-content/uploads/2020/07/Revised-Guidance-Note-on-applications-for-Prior-Authorisation-adopted-by-Members-30-Jan14.pdf)).
   LiveKit Cloud offers only US (default) and EU data regions, fixed at project creation
   ([LiveKit](https://docs.livekit.io/deploy/admin/regions/data-residency)). Choose regions before creating projects.
5. **Doctors must be registered where the patient is, and the platform must check.** Zimbabwe: MDPCZ registration plus
   a current practising certificate ([HPA ss 92, 95, 121](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf)).
   South Africa: HPCSA registration; a foreign practitioner treating SA patients by telehealth must be registered at home
   and with the HPCSA, and must verify the patient's identity and location
   ([HPA 56 of 1974 s 17](https://www.acts.co.za/health/17__registration_a_prerequisite_for_practising);
   [HPCSA Booklet 10 paras 5.1(c), 5.6(d)](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf)).
   CareNexus stores one licence number, body and country, no expiry, no patient country and no session location.
6. **Telehealth has explicit practice standards in South Africa.** Express informed consent to telehealth, recorded per
   practitioner with set contents (practice number, security measures, who else is on the call, cost); no prescribing
   from an online questionnaire alone; no practitioner may work only through telehealth; social media platforms are not
   suitable ([Booklet 10 paras 3.4, 3.8, 5.5, 5.7](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf)).
   Zimbabwe's MDPCZ policy, known only through a press summary, asks for explicit consent and documented encounters too
   ([Techzim](https://www.techzim.co.zw/2022/10/what-you-should-know-about-the-recently-published-mdpcz-policy-on-telemedicine/)).
7. **Medical records must keep their history, be kept for years, and be shielded from admins.** Corrections leave the
   original visible; nothing is erased. Keep at least 10 years from last entry in Zimbabwe
   ([MDPCZ records policy](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf)); in South
   Africa ideally indefinitely, otherwise at least 6 years from dormancy, minors until age 21, incapacitated patients for
   life ([HPCSA Booklet 9, 2022](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/11/Booklet-9-Keeping-of-Patient-Records_Review-Draft_-FINAL_Sept-2022.pdf)).
   Changing records without authority, or weak access controls, is a crime in both countries
   ([MSAA 2026 s 8H](https://becomingthemuse.net/wp-content/uploads/2026/07/medical-services-amendment-act-number-3-of-2026.pdf);
   [NHA s 17](https://www.acts.co.za/national-health/17__protection_of_health_records)). CareNexus currently overwrites
   medical records in place and lets administrators read all clinical content.
8. **E-prescriptions are not yet valid as built.** South Africa requires an advanced electronic signature (AES) and
   particulars CareNexus does not capture (patient ID number, address, age and gender, prescriber registration number);
   HPCSA rule 17(2) still requires Schedule 5-8 prescriptions to be handwritten
   ([Medicines Act General Regulation 33](https://www.acts.co.za/medicines-/n859_33__particulars_which_mus);
   [ECTA s 13](https://www.acts.co.za/electronic-communications-and-transactions-act-2002/13__signature);
   [HPCSA Ethical Rules](https://ethiqal.co.za/wp-content/uploads/2019/08/HPCSA-Ethical-and-Professional-Rules-of-the-Health-Profession-Council.pdf)).
   Zimbabwe requires a prescription "signed in full", at most 30 days' supply with 5 repeats, and first dispensing
   within 3 months, and has no e-prescribing standard
   ([S.I. 150 of 1991 ss 61, 74](https://mdpcz.co.zw/external_policies/1682978281Medicines%20and%20Allied%20Substances%20Control%20General%20Regulations%201991SI%20150%20of%201991.pdf)).
9. **Breach reporting is fast in Zimbabwe.** POTRAZ within 24 hours on Form DP3; affected people within 72 hours if the
   risk is high ([SI 155 reg 17](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf)).
   South Africa: the Regulator through eServices and the people affected "as soon as reasonably possible", with no risk
   threshold ([POPIA s 22](https://popia.co.za/section-22-notification-of-security-compromises/);
   [Regulator](https://inforegulator.org.za/popia/)).
10. **Children need verified guardian consent.** Both countries treat everyone under 18 as a child for data purposes;
    Zimbabwe wants documentary proof of guardianship and no automated decisions about children
    ([CDPG 2 of 2024](https://www.potraz.gov.zw/wp-content/uploads/2026/03/Processing-of-Childrens-Personal-Information-Final-002.pdf);
    [POPIA ss 34-35](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)). HPCSA
    guidance gives patients aged 12 and over access to their own records, so parent proxies need care
    ([Booklet 9, 2016](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-9-Guidelines-on-Patient-Records.pdf)).
    CareNexus has only a caregiver's self-attestation and no age logic.
11. **Who may collect payments is regulated, and the billing defaults are Kenyan.** In South Africa, collecting patient
    fees to pay doctors makes CareNexus a third-party payment provider that must register (now with the SARB, since 11
    August 2026) unless each practice is the merchant through a registered provider
    ([SARB PSMB](https://www.resbank.co.za/en/home/what-we-do/payments-and-settlements/psmb)). In Zimbabwe, accepting
    money to pay to a third party is reserved to payment-system participants unless CareNexus is the practice's appointed
    agent ([NPS Act s 18](https://www.rbz.co.zw/documents/acts/nationalpaymentsystem_act.pdf)). Billing defaults to KES
    and offers M-PESA, which is not available to consumers in either country
    ([NextBillion](https://nextbillion.net/news/vodacom-pulls-plug-on-mobile-money-service-m-pesa-in-south-africa)).
    Zimbabwe needs USD and ZiG ([RBZ FXD5/2026](https://www.rbz.co.zw/documents/Regulations_Acts/2026/Foreign_Exchange_Guidelines_FXD5_version_2._March_2026.pdf)).
12. **Consumer law shapes the terms, checkout and refunds.** Both countries require a list of supplier disclosures on
    the site, a review-and-correct step before payment, and a 7-day cooling-off for online services. South Africa
    excludes services already started with consent; Zimbabwe has no such exclusion, and a "no refund" notice is a crime
    there (up to level 12 or 5 years). Liability limits and risk warnings ("not an emergency service", "alerts are not
    a diagnosis") must be drawn to the user's attention, with separate assent in SA
    ([ECTA ss 42-44](https://www.gov.za/sites/default/files/gcis_document/201409/a25-02.pdf);
    [CPA ss 22, 49, 51](https://www.gov.za/sites/default/files/gcis_document/201409/321864670.pdf);
    [ZW CPA ss 41-43, 52-53](https://commons.laws.africa/akn/zw/act/2019/5/eng@2019-12-10.pdf)). The shipped terms and
    privacy notice are placeholders, and South Africa also needs a PAIA manual
    ([PAIA s 51](https://www.acts.co.za/promotion-of-access-to-/51__manual)).
13. **AI risk alerts may be a medical device in South Africa and carry HPCSA disclosure duties.** SAHPRA treats
    predictive risk algorithms and clinical decision support as software as a medical device, needing a s 22C
    establishment licence (operating without one is an offence, up to 10 years)
    ([SAHPRA MD08](https://www.sahpra.org.za/wp-content/uploads/2025/09/MD08-20252026_-SAHPRA-Communication-to-Industry-AI-Medical-devices_Acknowledgements.pdf);
    [Medicines Act s 22C](https://www.acts.co.za/medicines-/22c__licensing)). HPCSA Booklet 20 requires telling patients
    when AI assisted, explaining limits, and not disadvantaging patients who decline AI
    ([Booklet 20](https://www.hpcsa-blogs.co.za/wp-content/uploads/2025/11/ETHICAL-GUIDELINES-USE-OF-AI.pdf)). Zimbabwe
    has no device rules for software yet ([MCAZ](https://www.mcaz.co.zw/documents/medical-devices-docs/regulations-and-guidelines-docs/)).
    The existing doctor-review step is the right base; intended-use wording decides the rest.

---

## 3. Country requirements

### 3.1 Zimbabwe

#### 3.1.1 Registrations and licences

| What | Who | Cost | Deadline | Source |
|---|---|---|---|---|
| POTRAZ data controller licence (Form DP1). Declare health data as sensitive, business type "Health Administration and Patient care", whether data is located in Zimbabwe and if not which country, and your safeguards. | The CareNexus entity that decides how data is used. Count every data subject (patients, clinicians, caregivers, staff, website users) to pick the tier. | Tier 1 (50-1,000): US$50. Tier 2 (1,001-100,000): US$30 application + US$300. Tier 3 (100,001-500,000): US$30 + US$500. Tier 4 (over 500,000): US$30 + US$2,500. Annual. Payable in USD or ZiG. | Before processing starts. POTRAZ decides within 14 days. Renew at least 3 months before the 12-month expiry. | [SI 155, regs 3-7, Second Schedule](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf) |
| Data Protection Officer, notified on Form DP2. Must hold POTRAZ-approved certification (course US$1,250 for citizens, US$1,450 international, plus US$30 or US$50 application). Report contact changes or departure within 14 days; replace within 90 days. | CareNexus governance | Course fees above, plus continuing professional development | With the licence. Failing to appoint: up to level 7 (US$400) or 2 years. | [SI 155, regs 12-14](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf). Whether the DPO can be outsourced or based outside Zimbabwe is not known: the POTRAZ DPO guidelines are a scan with no readable text ([Veritas](https://www.veritaszim.net/node/7378)). |
| Notification of processing to POTRAZ before automated processing starts, and of changes (identity, legal basis, purposes, data categories with detail on sensitive data, data subjects, recipients, retention, security, processors, planned transfers). Also notify any plan to transfer data abroad and any biometric or genetic processing. | CareNexus | None found | Before launch and before each new feature that changes processing (for example an AI provider) | [CDPA ss 20-23](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf); [SI 155 reg 10(2)](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf). POTRAZ may exempt controllers that have a DPO (s 20(4)(b)). |
| Cross-border transfer notice, DPIA and prior authorisation letter | CareNexus | None found | Before data goes to an offshore host or provider | [CDPG 5 of 2024, paras 3, 5.2](https://www.veritaszim.net/sites/veritas_d/files/05%20Guideline%20on%20cross%20border%20transfer.pdf) |
| Representative in Zimbabwe, if the controller is not established there but uses means in Zimbabwe | A South African or offshore contracting entity | - | Before launch | [CDPA s 4(2)-(3)](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf) |
| Health institution registration with the Health Professions Authority, if CareNexus is a "health institution" (premises used for diagnosis or treatment). The 2023 MDPCZ records policy says every organisation holding clinical records must be HPA-registered. | Open question; at minimum each doctor's premises | Not researched | Before launch if it applies. Operating or practising from an unregistered institution (knowingly): up to level 7 or 2 years. | [HPA ss 2, 99-105](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf); [MDPCZ records policy s 14](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf) |
| Doctors: MDPCZ registration plus a current practising certificate (valid for the period the council sets, at most 12 months). Pharmacists: Pharmacists Council registration. | Each clinician; CareNexus should verify | Set by the councils | Before they see Zimbabwean patients | [HPA ss 92, 95, 121, 124](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf) |
| MCAZ dispensing licence, only if doctors on CareNexus supply medicines directly. Not granted within 5 km of a pharmacy unless waived; runs 12 months from 1 July. | Each dispensing doctor | Not researched | Before any supply feature | [S.I. 150 of 1991 ss 10-13](https://mdpcz.co.zw/external_policies/1682978281Medicines%20and%20Allied%20Substances%20Control%20General%20Regulations%201991SI%20150%20of%201991.pdf) |
| VAT registration with ZIMRA once taxable supplies exceed US$25,000 (or ZiG equivalent) in 12 months, including an offshore supplier of digital services to Zimbabwean users | The entity that charges platform or subscription fees | - | When the threshold is reached | [ZIMRA](https://www.zimra.co.zw/domestic-taxes/vat/mechanics-of-vat); [KPMG on s 13A](https://kpmg.com/us/en/taxnewsflash/news/2026/01/tnf-zimbabwe-digital-services-withholding-in-lieu-of-nonresident-vat-collection.html) |
| RBZ registration of service agreements with recurring fees paid offshore (software, technical services: likely hosting, LiveKit, SMS, AI). Once-off payments and subscriptions up to US$250,000 a year per company per bank go through the bank without registration. | A Zimbabwean entity paying offshore vendors | - | Before paying under a recurring agreement; renew yearly | [RBZ FXD5/2026 ss 3.1.5-3.1.10](https://www.rbz.co.zw/documents/Regulations_Acts/2026/Foreign_Exchange_Guidelines_FXD5_version_2._March_2026.pdf) |

#### 3.1.2 Privacy obligations (Cyber and Data Protection Act and SI 155 of 2024)

- **Consent for sensitive data.** Written consent, withdrawable at any time for free. "Sensitive data" covers health
  and genetic data and also gender, age, marital or family status and criminal, educational, financial or employment
  history, so date of birth, gender, caregiver relationships and billing data are all sensitive. The s 11(5) exceptions
  are narrow (employment law, vital interests, non-profit bodies about their members, national security, legal claims,
  data made public, research, law in the substantial public interest); there is no "performance of contract" or
  "legitimate interests" exception for sensitive data
  ([CDPA ss 3, 11](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)).
  Breach: up to level 11 (US$1,000) or 7 years, and the court can order the data deleted (s 33).
- **Health data specifically.** Prohibited without written consent unless an exception applies; the main one is
  processing for diagnosis, care or treatment under a health professional's supervision (s 12(3)(j)). Health data may
  be processed only under a health professional's responsibility unless the patient consents in writing or it prevents
  imminent danger (s 12(4)). It may be collected from other people (for example a caregiver) only if the patient
  cannot provide it (s 12(6)). Staff with access are bound by professional secrecy (s 12(7)). Section 12(8)-(9)
  requires a unique patient identifier issued by a designated public authority; no such authority was found
  ([CDPA s 12](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)).
- **Principles.** Processing must be necessary, fair and lawful; for specified purposes; adequate and not excessive;
  accurate; kept identifiable no longer than necessary (s 13 breaches are an offence, level 11 or 7 years). Non-sensitive
  data needs consent or one of five other grounds (evidence of an offence, legal obligation, vital interests, public
  interest, legitimate interests)
  ([CDPA ss 7-10, 13](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)).
- **Rights and notices.** Right to be informed, access, object, and correct or delete false or misleading data. The
  notice at collection must give the controller's name and address, purposes, the right to object free of charge to
  direct marketing, whether answering is compulsory, and (as necessary) recipients and access and correction rights.
  People whose data comes from someone else (a caregiver) must also be told
  ([CDPA ss 14-16](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)).
  The Act sets no response deadline and no portability right; the MDPCZ policy expects patient access requests
  within 30 days ([MDPCZ records policy, Appendix 2](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf)).
- **Security and accountability.** Appropriate technical and organisational measures; risk assessments, policies,
  regular testing, timely restore after incidents; be able to demonstrate compliance to POTRAZ
  ([CDPA ss 18, 24](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf);
  [SI 155 reg 16](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf)).
  Neither instrument names a standard such as ISO 27001.
- **Processors.** Written contract binding each processor to security measures; processors act only on instructions;
  the controller is liable for them
  ([CDPA ss 17, 18(4)-(5)](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf);
  [SI 155 reg 10(4)](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf)).
- **Breaches.** POTRAZ within 24 hours of becoming aware (Form DP3: dates, systems, data categories, approximate
  records, impact, measures); affected people within 72 hours if high risk; answer POTRAZ within 14 days; final report
  within 21 days; keep a breach register
  ([SI 155 reg 17, Fourth Schedule](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf)).
- **Children (under 18).** Parent or guardian consent with reasonable verification; regular DPIAs; privacy by design;
  no automated decisions affecting children's rights
  ([SI 155 reg 10(5)](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf)).
  The POTRAZ guideline goes further: written guardian consent, documents proving guardianship (birth certificate,
  custody or guardianship order), up-front age verification, age-appropriate notices, deletion mechanisms, re-consent
  at 18, and prior authorisation to send children's data to a country without adequate protection
  ([CDPG 2 of 2024](https://www.potraz.gov.zw/wp-content/uploads/2026/03/Processing-of-Childrens-Personal-Information-Final-002.pdf)).
- **Incapacitated adults.** Their rights are exercised by a parent, guardian or court-designated person, with
  incapacity proven by a physician
  ([CDPA s 27](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)).
- **Direct marketing.** People must be told at collection that they may object free of charge; consumers may refuse or
  block marketing, and every unsolicited electronic message needs an unsubscribe option (Consumer Protection Act s 54:
  up to level 5 or 6 months). Zimbabwe works on opt-out, but using health data for targeting still needs written
  consent ([CDPA s 15(1)(c)](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf);
  [ZW CPA ss 49, 54](https://commons.laws.africa/akn/zw/act/2019/5/eng@2019-12-10.pdf)).
- **Inspections.** POTRAZ inspections from 1 September 2026 check the licence and DPO
  ([Veritas Bill Watch 27/2026](https://www.veritaszim.net/node/8052)). The Herald names the notice as Regulatory
  Notice 2 of 2026 and says governance, secure storage, access control, disposal and breach reporting will also be
  examined ([Herald](https://www.heraldonline.co.zw/explainer-from-creches-to-clinics-what-potrazs-data-inspections-mean-for-everyone/));
  Veritas gives no notice number.

#### 3.1.3 Health-sector obligations

**Telehealth**

- Only MDPCZ-registered practitioners may practise medicine for gain on patients in Zimbabwe (s 121: up to level 10 or
  5 years), and only with a current practising certificate (s 92: up to level 6 or 1 year)
  ([HPA](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf)).
- A company must not imply that it is a registered practice (s 127: company up to level 6). Describe CareNexus as a
  platform connecting patients with independently registered practitioners, not as a clinic
  ([HPA s 127](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf)).
  Note that the `CLINIC_NAME` system setting is shown in the app.
- Fees for work by someone not on the Zimbabwe register cannot be recovered, and statutory certificates (sick notes)
  they sign are invalid (ss 118-119)
  ([HPA](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf)).
- MDPCZ Telemedicine Policy (published July 2022 as a redraft of the 2014 policy), as summarised by Techzim: explicit
  patient consent; privacy; encounters documented and stored securely (a WhatsApp chat is not a medical record);
  e-prescriptions to national security standards; telemedicine from registered premises where face-to-face care is
  possible; confirm identity and history with the patient's regular practitioner; give the nearest public health
  institution when no clinician is available; the doctor stays responsible even if the platform causes a breach. The
  MDPCZ has no direct power over technology companies; it disciplines doctors (HPA s 113)
  ([Techzim summary](https://www.techzim.co.zw/2022/10/what-you-should-know-about-the-recently-published-mdpcz-policy-on-telemedicine/)).
  The official text is not on the MDPCZ website; obtain it from the council. The MDPCZ declared Econet's Dial-a-Doc
  service prohibited in 2016 ([Techzim 2016](https://www.techzim.co.zw/2016/03/econets-dial-doc-service-prohibited-maintains-medical-practitioners-council-zimbabwe)).
  A separate "international telemedicine policy" requiring MDPCZ registration of foreign doctors is reported but its
  text was not found (unverified).
- Informed consent: the practitioner must explain health status, options, benefits, risks, **costs** and the right to
  refuse, in a language the patient understands (s 34: level 8 or 1 year; s 35: level 10 or 1 year). Substitute consent
  follows a set order: written mandate or legally authorised person, then spouse or partner, parent, guardian, adult
  child, grandparent, adult sibling
  ([Public Health Act ss 34-35](https://bwcimplementation.org/sites/default/files/resource/ZW_Public%20Health%20Act%202018_EN.pdf);
  [Medical Services Amendment Act 2026 ss 8A-8C](https://becomingthemuse.net/wp-content/uploads/2026/07/medical-services-amendment-act-number-3-of-2026.pdf)).
- Health services for research, including a pilot of AI alerts that counts as research, need prior written
  authorisation from the patient, the treating provider, the institution head and an ethics authority
  ([MSAA 2026 s 8F](https://www.veritaszim.net/sites/veritas_d/files/Medical%20Services%20Amendment%2C%20Act%20No.%203%20of%202026.pdf)).
- Cabinet approved principles for a Health Professions Bill to "make provision for tele-health" on 29 October 2024; no
  Bill had been gazetted by November 2026
  ([Herald](https://www.heraldonline.co.zw/government-approves-key-principles-for-health-professions-act-amendment-to-facilitate-tele-health/)).

**Confidentiality, access and complaints**

- Patient information is confidential; disclose only with written consent, by law or court order, or for a serious
  public-health threat (s 39: up to level 8 or 1 year)
  ([Public Health Act s 39](https://bwcimplementation.org/sites/default/files/resource/ZW_Public%20Health%20Act%202018_EN.pdf)).
  The 2026 amendment adds a parallel duty and allows provider-to-provider sharing in the patient's interest; breaches
  are handled administratively (referral to a disciplinary body, suspension or deregistration of a private institution,
  s 15A) ([MSAA 2026 ss 8G, 15A](https://becomingthemuse.net/wp-content/uploads/2026/07/medical-services-amendment-act-number-3-of-2026.pdf)).
- A practitioner may look at records for treatment only with the patient's authorisation; research or teaching needs
  the patient, the head of the establishment and an ethics committee, unless data is de-identified (s 40: up to level
  8 or 2 years) ([Public Health Act s 40](https://faolex.fao.org/docs/pdf/zim190656.pdf)).
- Every health care provider (including its employees) must have control measures and a storage system preventing
  unauthorised access to records. It is an offence to falsify, change, delete or copy records without authority, fail
  to create or change a record when required, give false information for a record, link identifiers to clinical data,
  intercept records in transit, connect a records system to another without authority, or impair the record software
  (level 8 or 1 year) ([MSAA 2026 s 8H](https://becomingthemuse.net/wp-content/uploads/2026/07/medical-services-amendment-act-number-3-of-2026.pdf)).
  The Act has no commencement clause, so it applies from gazettal (reported as 7 July 2026). The Minister may set
  minimum storage standards under the new s 16(2)(e8); none found yet.
- Caregivers and guardians have no greater right to an adult patient's records than anyone else unless the patient
  consents or a court orders; parents may request access for under-18s. Audio or video recordings of a patient need
  express consent and form part of the record
  ([MDPCZ records policy s 7, Box 7](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf)).
- Every health establishment must have a complaints procedure that is visibly displayed, acknowledges every complaint
  and refers complaints it cannot handle to the right body (PHA s 41: up to level 8 or 6 months)
  ([Public Health Act s 41](https://bwcimplementation.org/sites/default/files/resource/ZW_Public%20Health%20Act%202018_EN.pdf);
  [MSAA 2026 s 8I](https://www.veritaszim.net/sites/veritas_d/files/Medical%20Services%20Amendment%2C%20Act%20No.%203%20of%202026.pdf)).

**Prescriptions**

- A prescription-only medicine needs a written prescription showing the prescriber's name, address and qualifications;
  the patient's name and address and age if under 12; the date; the registered name, strength and form; total daily
  dose and quantity; directions; legible; at most 30 days' supply with no more than 5 further 30-day supplies (except
  Tenth Schedule medicines); "signed in full" (s 74). An oral direction must be followed by a written prescription
  within 7 days. A prescription cannot be dispensed for the first time more than 3 months after issue (s 61).
  Specially restricted preparations need identity and prior-supply checks, "dispense once only", and a countersignature
  by a listed person (ss 70-72). Penalty: level 7 or 6 months (s 106)
  ([S.I. 150 of 1991](https://mdpcz.co.zw/external_policies/1682978281Medicines%20and%20Allied%20Substances%20Control%20General%20Regulations%201991SI%20150%20of%201991.pdf)).
- Prescriptions must not be altered; discontinue and write a new one. Use generic drug names, never abbreviated
  ([MDPCZ records policy, Box 1 and Appendix 3](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf)).
- No MCAZ instrument recognising electronic prescriptions or e-signatures was found, and the national e-prescribing
  standard the MDPCZ policy refers to does not exist yet. Pharmacies may refuse e-scripts without a wet signature
  (section 8).

**Records and retention**

- Doctors keep records 10 years from the last entry; institutions indefinitely or at least 10 years, then to the
  National Archives. Destruction must be secure, with a destruction register (patient, record dates, disposal date,
  who disposed). Every practice needs a records management policy, which the policy calls a condition of institution
  registration ([MDPCZ records policy ss 9-11](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf)).
- Corrections keep the original visible with author, time and reason; each entry dated (24-hour clock), timed and
  attributed; late entries flagged retrospective; patient name and ID on every screen; records in English
  ([MDPCZ records policy s 4, s 14 and Appendix 3](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf)).
- Health institutions must keep the records Medical Services Act s 15 requires of private hospitals (PHA s 38(2):
  level 13 and 2 years) ([Public Health Act s 38](https://faolex.fao.org/docs/pdf/zim190656.pdf)). The content of MSA
  s 15 was not obtained, and no statutory retention period was found in the CDPA, the 2026 amendment or MDPCZ sources
  beyond the policy above.

**Medical devices and digital health**

- MCAZ has no medical-device classification, registration or software-as-a-medical-device rule; its device regulations
  page lists only gloves and condoms. Draft device regulations are reported but not gazetted
  ([MCAZ](https://www.mcaz.co.zw/documents/medical-devices-docs/regulations-and-guidelines-docs/)).
- National direction is an OpenHIE-style exchange using HL7 FHIR, with Impilo as the public EHR; no binding standard for
  private platforms. The National Health Strategy 2026-2030 commits to digital health but sets no interoperability rule
  ([Global Fund case study](https://www.theglobalfund.org/media/qksld2tg/publication_zimbabwe-digital-health_casestudy_en.pdf);
  [NewsDay](https://www.newsday.co.zw/local-news/article/200058199/national-health-strategy-unveiled)).

#### 3.1.4 Consumer and online terms

Zimbabwe's Consumer Protection Act [Chapter 14:44] (Act 5 of 2019) applies whether the supplier is inside or outside
Zimbabwe (s 3(3)(a)); large juristic consumers may be excluded (s 3(2)(b))
([ZW CPA](https://commons.laws.africa/akn/zw/act/2019/5/eng@2019-12-10.pdf)).

- **Online disclosures (s 52(1)).** Publish where services are offered: full name and legal status; physical address
  and phone; website and email; registration number and office bearers; address for legal service; accreditation
  bodies and codes; a description of the service; the full price including taxes and fees; method of payment; the terms
  and how to access, store and reproduce them; when the service is rendered; how to access transaction records; the
  refund policy; any ADR code; security and privacy policy for payments; minimum duration of recurring agreements; the
  s 53 cooling-off right. Missing items let the consumer cancel within 7 days for a refund (s 52(3)-(4)).
- **Checkout and payment security (s 52(2), (5)-(6)).** Let the consumer review the transaction, correct mistakes and
  withdraw before the final order. Use a payment system "sufficiently secure" by accepted standards; the supplier is
  liable for damage from an insecure one.
- **Cooling-off (s 53).** A consumer may cancel any electronic transaction for services within 7 days of the agreement,
  without reason or penalty, with a full refund within 14 days. Unlike South Africa, there is **no exclusion for
  services already performed**, and there is no case law on deducting a completed consultation.
- **Unfair terms and disclaimers (ss 41-43).** Terms must not be excessively one-sided (s 41: offence, level 5 or 6
  months). Any notice that disclaims liability or denies a consumer right, specifically "no refund", "no returns" or "no
  exchanges", is an offence (s 42: up to level 12 or 5 years). Terms that limit liability, shift risk, impose an
  indemnity or acknowledge facts must be drawn to the consumer's attention (s 43). Frame telehealth and AI-alert limits
  as clinical-limitation notices, not rights waivers.
- **Plain language and prices (ss 30, 26).** Describe services in plain language. Display a price "expressed in the
  currency being used by the country", including all taxes and levies, and never charge more than displayed; if several
  prices are shown the lowest applies (s 26: level 5 or 3 months per count). Showing both USD and ZiG is the cautious
  reading.
- **Fixed-term plans (s 23).** Consumers may cancel on 20 business days' notice (a reasonable cancellation penalty is
  allowed); send a renewal notice 40 to 80 business days before expiry; the plan then runs month to month.
- **Penalties.** Courts may impose administrative fines up to 2.5% of the previous year's net profit or another amount
  (s 80).
- **Electronic contracts.** Zimbabwe has no enacted electronic transactions or e-signature law; the Electronic
  Transactions and Electronic Commerce Bill was still unpublished in February 2026
  ([Veritas via The Zimbabwean](https://www.thezimbabwean.co/2026/02/legislative-agenda-for-new-session-of-parliament/)).
  Click-through contracts rest on common law plus CPA Part IV, so keep strong evidence of acceptance (versioned text,
  timestamp, identity, MFA, immutable audit). Whether a click-through counts as "written" consent under the CDPA, PHA
  s 39 and MSA s 8G is open (section 8).

#### 3.1.5 Payments, currency and tax

- **Currency.** Domestic transactions may be priced and settled in foreign currency (mainly USD) or ZiG. The move to
  ZiG only is "not date-based" but tied to conditions under NDS2 (2026-2030), so build currency as configuration.
  Store the currency on each invoice, refund in the same currency, and record the exchange rate for any conversion.
  FX non-compliance penalty: the greater of 1% of the transaction or US$100,000
  ([RBZ FXD5/2026 ss 1.1, 7.1.3](https://www.rbz.co.zw/documents/Regulations_Acts/2026/Foreign_Exchange_Guidelines_FXD5_version_2._March_2026.pdf)).
  Penalties for pricing above the official rate (SI 81A of 2024) were repealed by SI 34 of 2025, per news reports; Veritas
  has questioned whether that repeal was validly made
  ([NewZWire](https://newzwire.live/govt-drops-penalties-for-charging-above-official-exchange-rate/)).
- **Local payment methods.** EcoCash, OneMoney, InnBucks, O'mari, Zimswitch and Visa/Mastercard are available through an
  aggregator such as Paynow ([Paynow docs](https://developers.paynow.co.zw/docs/paynow/express_checkout_transactions)).
  M-PESA has never been offered in Zimbabwe.
- **Who may collect.** Only a participant in a recognised payment system, or someone it introduces, may as a regular
  business accept money to pay it on to a third person (offence: a fine of "one hundred thousand dollars" or 1 year).
  Exemptions include a duly appointed agent of the payee (s 18(3)(c)) and transfers within a group (s 18(3)(d)).
  Safest designs: each practice is the merchant through a bank or RBZ-approved aggregator, or CareNexus is formally
  appointed as each practice's collection agent (confirm with counsel and the RBZ). Do not run a CareNexus wallet or
  stored balance ([NPS Act s 18](https://www.rbz.co.zw/documents/acts/nationalpaymentsystem_act.pdf)). Later
  amendments to the Act and a reported payment-services Bill were not verified.
- **Offshore vendor payments.** Recurring fees to offshore software or technical providers must be registered with the
  RBZ through a bank (1 year at a time), with a declaration that service payments stay under 3% of gross revenue;
  once-off payments and subscriptions up to US$250,000 a year per company per bank go through the bank
  ([FXD5 ss 3.1.5-3.1.10](https://www.rbz.co.zw/documents/Regulations_Acts/2026/Foreign_Exchange_Guidelines_FXD5_version_2._March_2026.pdf)).
  Not relevant if a non-Zimbabwean entity pays the vendors.
- **Earnings from South Africa.** If a Zimbabwean entity earns from SA patients or practices, those are service exports:
  payment within 90 days, 30% of receipts surrendered at the interbank rate (50% if overdue), declared on Form GSD
  ([FXD5 ss 3.2.2-3.2.4](https://www.rbz.co.zw/documents/Regulations_Acts/2026/Foreign_Exchange_Guidelines_FXD5_version_2._March_2026.pdf)).
- **VAT.** Medical services are exempt; platform or subscription fees may be standard-rated at 15.5%. Register above
  US$25,000 a year ([ZIMRA](https://www.zimra.co.zw/domestic-taxes/vat/mechanics-of-vat)). From 1 January 2026,
  banks and mobile money operators withhold 15.5% on payments to non-resident digital suppliers not registered for VAT
  in Zimbabwe; registered non-residents issue fiscalised invoices through FDMS. Sources differ on the withholding
  fraction for registered suppliers, and ZIMRA Public Notice 05 of 2026 was not read
  ([KPMG](https://kpmg.com/us/en/taxnewsflash/news/2026/01/tnf-zimbabwe-digital-services-withholding-in-lieu-of-nonresident-vat-collection.html)).
- **IMTT.** Electronic transfers, including EcoCash and OneMoney, attract Intermediated Money Transfer Tax withheld at
  source: 1.5% on ZiG and 2% on foreign currency from 1 January 2026 (Finance Act (No. 7) of 2025). Model it in fees,
  payouts and reconciliation ([Financial Gazette](https://fingaz.co.zw/2026/05/06/unpacking-intermediated-money-transfer-tax/)).
- **Data points.** Financial history is "sensitive data", so billing data needs written consent
  ([CDPA s 3](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)).
  Do not bill for consultations by clinicians not on the Zimbabwe register
  ([HPA s 118](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf)).

### 3.2 South Africa

#### 3.2.1 Registrations and filings

| What | Who | Cost | Deadline | Source |
|---|---|---|---|---|
| Register the Information Officer (default: the head of the private body) and any deputies on eServices. Not registering is not an offence, but the head is then held liable for POPIA matters. | SA operating entity | None found | Before processing; the officer cannot act until registered (s 55(2)) | [POPIA ss 55-56](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf); [Information Regulator](https://inforegulator.org.za/popia/) |
| Prior authorisation to transfer health or children's data to a foreign country without adequate protection (includes storage). Apply once, by form, to popiacompliance@inforegulator.org.za. | SA responsible party | None found | Before the transfer. Regulator says within 4 weeks whether it will investigate; investigation capped at 13 weeks; silence then counts as approval (s 58(7)). Not applying is an offence (s 59: fine or 12 months), which also opens a s 109 fine up to R10m. | [POPIA ss 57-59](https://popia.co.za/section-57-processing-subject-to-prior-authorisation/); [Guidance Note, Jan 2024](https://inforegulator.org.za/wp-content/uploads/2020/07/Revised-Guidance-Note-on-applications-for-Prior-Authorisation-adopted-by-Members-30-Jan14.pdf) |
| Prior authorisation to use unique identifiers (patient or ID numbers) for another purpose to link with other parties' data (labs, pharmacies, insurers) | SA responsible party | - | Before such linking | [POPIA s 57(1)(a)](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf) |
| PAIA manual, published on the website and at the office, updated regularly | SA entity | - | Before launch. The small-private-body exemption lapsed on 31 Dec 2021 (Webber Wentzel, SAICA, MJK). | [PAIA s 51](https://www.acts.co.za/promotion-of-access-to-/51__manual) |
| Doctors: HPCSA registration as independent practitioners; foreign practitioners treating SA patients by telehealth also need HPCSA registration | Each clinician; CareNexus should verify | Set by HPCSA | Before they see SA patients. Practising unregistered: fine or 12 months (s 17(5)); falsely claiming registration is a separate offence (s 40). | [HPA 56 of 1974 s 17](https://www.acts.co.za/health/17__registration_a_prerequisite_for_practising); [s 40](https://www.acts.co.za/health_professions_/40__penalty_for_professing_to_be_a_registered_person_or_the_holder_of_certain_qualifications); [Booklet 10 para 5.1](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf) |
| HPCSA approval as an employer, if a CareNexus company employs or appoints doctors (rule 18 as amended by BN 856 of 21 Nov 2025; OTASA reports a 31 May 2027 compliance deadline) | CareNexus, if it employs clinicians | - | Before employing doctors | [Werksmans](https://werksmans.com/back-to-the-future-amendment-of-rule-18-of-the-ethical-rules-leaves-practitioners-in-uncertain-territory/) |
| Third-party payment provider registration with the SARB (moved from PASA on 11 Aug 2026; TPPP@resbank.co.za), if CareNexus collects patient money to pay practices. A draft Authorisation Framework (capital tiers R500k / R2m) is not yet final. | CareNexus, unless each practice is merchant of record through a registered PSP | - | Before collecting | [SARB PSMB](https://www.resbank.co.za/en/home/what-we-do/payments-and-settlements/psmb); [LDD](https://www.ldd.co.za/popia-payment-aggregators-south-africa/) |
| VAT registration: compulsory above R2.3 million taxable supplies in 12 months (from 1 Apr 2026, not yet promulgated); voluntary from R120,000. A foreign supplier of electronic services to SA consumers also registers above R2.3 million. | The entity that charges SA users | - | End of the month the threshold is passed | [SARS Budget 2026 FAQ](https://www.sars.gov.za/about/sars-tax-and-customs-system/budget/budget-2026-frequently-asked-questions/); [SARS VAT-REG-02-G02](https://www.sars.gov.za/wp-content/uploads/Ops/Guides/VAT-REG-02-G02-Supply-of-Electronic-Services-by-Foreign-Suppliers-and-Foreign-Intermediaries-External-Guide.pdf) |
| SAHPRA medical device establishment licence (s 22C), only if AI or rule-based alerts are marketed for diagnosis, monitoring or risk prediction | CareNexus as SaMD "manufacturer" | Not researched | Before marketing such a feature in SA. Operating without it: offence, fine or 10 years (ss 22C(6), 29(k), 30(1)). | [SAHPRA MD08](https://www.sahpra.org.za/wp-content/uploads/2025/09/MD08-20252026_-SAHPRA-Communication-to-Industry-AI-Medical-devices_Acknowledgements.pdf); [Medicines Act s 22C](https://www.acts.co.za/medicines-/22c__licensing) |

#### 3.2.2 Privacy obligations (POPIA)

- **Scope and role.** POPIA applies to a responsible party domiciled in SA, or one elsewhere using means in SA. Decide
  whether CareNexus is the responsible party or an operator for practices
  ([POPIA s 3](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)).
- **Health data.** Special personal information; prohibited unless authorised. Section 32(1)(a) covers medical
  professionals and healthcare institutions; s 32(1)(b) covers insurers, schemes, administrators and managed-care
  organisations. Otherwise get consent that is voluntary, specific and informed, with the burden of proof on CareNexus.
  Everyone handling health data must be bound by confidentiality (s 32(2)-(3))
  ([POPIA ss 11, 26, 27, 32](https://popia.co.za/section-32-authorisation-concerning-data-subjects-health-or-sex-life/)).
- **Health information regulations 2026** (GN 7198, in force 6 March 2026) apply to insurers, medical schemes,
  administrators, managed-care organisations, administrative bodies, pension funds, employers, institutions working for
  them, and their "applicable operators": security for health records, confidentiality, proper disposal, and s 72 for
  transfers. Whether they reach CareNexus as an operator is an inference
  ([GN 7198, GG 54268](https://www.gov.za/sites/default/files/gcis_document/202603/54268gon7198.pdf)).
- **Children (under 18).** Prior consent of a competent person (parent or guardian)
  ([POPIA ss 34-35](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)).
  Health-sector rules use other ages: HPCSA guidance gives patients aged 12 and over access to their own records and
  requires a patient's written authorisation before a parent of an under-16 sees them
  ([Booklet 9, 2016, para 11](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-9-Guidelines-on-Patient-Records.pdf));
  disclosure for a child under 12 needs the parent's written consent
  ([HPCSA rule 13](https://ethiqal.co.za/wp-content/uploads/2019/08/HPCSA-Ethical-and-Professional-Rules-of-the-Health-Profession-Council.pdf)).
  The Children's Act s 129 treatment-consent age of 12 was not checked against its text (unverified).
- **Notice and lawful basis.** Document a lawful basis per activity. Before collection tell users what is collected,
  who you are, why, whether it is mandatory, intended foreign transfers and their protection level, recipients, their
  rights, and the right to complain to the Regulator with its contact details
  ([POPIA ss 11, 18](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)).
  Since 17 April 2025 (GN 6126, GG 52523) objections and correction or deletion requests must be free and accepted by
  hand, fax, post, email, SMS, WhatsApp or telephone; telephone requests are recorded and the recording made available
  on request ([Eversheds summary](https://www.eversheds-sutherland.com/en/south-africa/insights/new-amendments-to-popia-regulations-what-businesses-need-to-know);
  [Regulations as amended](https://inforegulator.org.za/wp-content/uploads/2025/04/POPIA-2021-Regulations-FINAL-21-Jan-2025.pdf)).
- **Access, correction, restriction.** Free confirmation of what is held; the record including the identity or
  categories of third parties who had access; correction or deletion of inaccurate data as soon as reasonably
  practicable; restriction while accuracy is disputed. Formal access requests run through PAIA; for health records
  that might seriously harm the person, a practitioner they nominate may be consulted first
  ([POPIA ss 14, 23, 24](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf);
  [PAIA s 61](https://www.acts.co.za/promotion-of-access-to-/61__access_to_health_or_other_records)).
- **Security and retention.** Identify risks, maintain and regularly verify safeguards, with regard to accepted
  security practice and professional rules. Keep records no longer than necessary unless law requires longer; then
  destroy or de-identify so they cannot be reconstructed
  ([POPIA ss 14, 19](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)).
  Medical-record periods come from HPCSA Booklet 9 (section 4.4).
- **Operators.** Written contract requiring security measures; operators notify CareNexus immediately of suspected
  unauthorised access
  ([POPIA ss 20-21](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)).
- **Breaches.** Notify the Regulator (eServices portal, mandatory since 1 April 2025; no risk threshold) and affected
  people as soon as reasonably possible, in writing, with consequences, measures and recommended steps
  ([POPIA s 22](https://popia.co.za/section-22-notification-of-security-compromises/);
  [Regulator](https://inforegulator.org.za/popia/);
  [Regulator fact sheet](https://inforegulator.org.za/wp-content/uploads/2025/08/Fact-Sheet-on-security-compromises-2.pdf)).
  A pathology lab paid R100,000 in 2025 after failing to notify and then ignoring an enforcement notice
  ([ITWeb](https://www.itweb.co.za/article/inforeg-exposes-popia-violators-as-data-breaches-mount/kLgB17ezby5M59N4)).
- **Direct marketing.** Electronic marketing (SMS, email, calls, push notifications, social DMs) needs opt-in on Form 4
  or in another free, accessible way, asked once, unless to an existing customer for similar products with an opt-out
  each time; silence and opt-out are not consent (reg 6.4). Every message identifies the sender and gives an opt-out.
  Appointment reminders, medication alerts and results notices are not marketing; keep them separate
  ([POPIA s 69](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf);
  [Regulator guidance note, Dec 2024](https://inforegulator.org.za/wp-content/uploads/2020/07/GUIDANCE-NOTE-ON-DIRECT-MARKETING-IN-TERMS-OF-THE-PROTECTION-OF-PERSONAL-INFORMATION-ACT-4-OF-2013-POPIA.pdf);
  [MJK summary](https://mjkinc.co.za/popia/regulations-2025-amendments)). ECTA s 45 separately makes it an offence to
  send unsolicited commercial messages without an opt-out (fine or 12 months)
  ([ECTA](https://www.gov.za/sites/default/files/gcis_document/201409/a25-02.pdf)).
- **Account numbers.** Mishandling account numbers (any identifier giving access to funds or credit) can be an offence
  (up to 10 years); taking all reasonable steps is a defence. Tokenise card and mobile-money details
  ([POPIA ss 105-107](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)).
- **Exposure.** Administrative fines up to R10 million, reached through an enforcement notice that is then ignored;
  health data and missing risk assessments aggravate (s 109(3)). Civil damages without proof of fault (s 99). Every fine
  so far followed an ignored enforcement notice
  ([POPIA ss 99, 107, 109](https://popia.co.za/section-109-administrative-fines/);
  [ITWeb on fines to date](https://www.itweb.co.za/article/inforeg-exposes-popia-violators-as-data-breaches-mount/kLgB17ezby5M59N4)).

#### 3.2.3 Health-sector obligations

**Records, confidentiality and access (National Health Act 61 of 2003)**

- The person in charge of a health establishment must make sure a health record is created and kept for every user
  ([NHA s 13](https://www.acts.co.za/national-health/13__obligation_to_keep_record)). Whether CareNexus is itself a
  "health establishment" or only the system its practices rely on is open; either way, for practices using CareNexus
  as their record system, its controls are the controls they rely on.
- All information about a user's health, treatment or stay is confidential and may be disclosed only with the user's
  **written** consent, by court order or law, or for a serious public-health threat
  ([NHA s 14](https://www.acts.co.za/national-health/14__confidentiality)). Caregiver access, insurer claims, an AI
  provider and notification content all fall under this. Provider-to-provider sharing is allowed for a legitimate
  purpose in the user's interest ([s 15](https://www.acts.co.za/national-health/15__access_to_health_records)).
- A provider may examine records for treatment only with the user's authorisation; research or teaching needs the user,
  the head of the establishment and an ethics committee unless anonymous
  ([s 16](https://www.acts.co.za/national-health/16__access_to_health_records_by_health_care_provider)).
- The person in charge must set up controls against unauthorised access to records and the system. It is an offence
  (fine or 1 year) to fail to do so, falsify records, create, change or destroy them without authority, fail to create
  or change one when required, copy without authority, link identity to condition without authority, gain unauthorised
  access or intercept, connect the record system to another without authority, or impair the record software
  ([NHA s 17](https://www.acts.co.za/national-health/17__protection_of_health_records)).

**Telehealth (HPCSA Booklet 10, December 2021, the latest version found)**

- No practitioner may practise exclusively through telehealth; a prior relationship is desirable but not compulsory;
  social media platforms such as WhatsApp are not desirable (paras 3.4, 3.7, 3.8).
- Cross-border practitioners treating SA patients must be registered at home and with the HPCSA; both collaborating
  practitioners must be HPCSA-registered; the practitioner in charge must be registered as independent (para 5.1(c)-(e)).
- The practitioner decides whether remote care is appropriate; a documented evaluation and history are needed before
  treating or prescribing; treatment or a prescription based solely on an online questionnaire is not acceptable
  (paras 5.4-5.5).
- Verify the patient's identity and **location**, and the other practitioner's identity, qualifications and location;
  make sure triage happens; give written emergency instructions and refer emergencies (paras 4.3, 5.1(g), 5.6(d)).
- **Consent (para 5.7).** The practitioner in charge must get express informed consent, written or recorded orally, to
  the treatment and to telehealth. It records: the patient and practitioner identities; that the practitioner decides
  whether telehealth is appropriate (face-to-face if no consensus); the practice number; the types of transmission
  consented to (prescriptions, refills, scheduling, education); security measures; material confidentiality risks;
  secure storage of any recordings; encryption of stored data; and express consent to send information to other
  practitioners and third parties. The patient must be told who will access the data, why, the cost and the
  implications; a copy goes in the record (and to the patient when required); in video calls the patient must know
  about and consent to anyone else present. CareNexus's terms-and-privacy acceptance is not a substitute.
- **Records and communications (paras 5.8-5.9).** Telehealth records must match face-to-face standards; transmissions
  (email, prescriptions, lab results) must be secured; all patient-practitioner electronic communications are filed in
  the medical record; the record includes messages, prescriptions, results, consultations and consent; transmit only
  clinically relevant data, only with the patient's knowledge and approval, encrypted and stripped of identifiers
  between sites; password-controlled access; third-party access authorised by the provider in charge under PAIA;
  practitioners make sure technical staff follow the rules.
  ([Booklet 10](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf))
- Breaches of Booklet 10 are unprofessional conduct, sanctioned against the practitioner (caution, fine, suspension,
  erasure). They bind doctors, not CareNexus, but the platform decides whether doctors can comply.

**Record keeping and retention (HPCSA Booklet 9)**

- No entry may be removed; corrections leave the original legible, are dated and signed, and give the reason. Keep
  billing records separate from clinical records. Patients aged 12 and over may access their own records
  ([Booklet 9, 2016](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-9-Guidelines-on-Patient-Records.pdf)).
- The September 2022 revision (marked a review draft; adoption unconfirmed) says: keep records ideally indefinitely,
  otherwise at least 6 years from dormancy; minors until their 21st birthday; mentally incapacitated patients for life;
  occupational health records 20 years; long-latency exposures at least 25 years; statutory periods prevail. Store and
  back up electronic data to international standards (it names ISO 27799) and protect confidentiality in transmission
  ([Booklet 9, 2022, paras 5-7](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/11/Booklet-9-Keeping-of-Patient-Records_Review-Draft_-FINAL_Sept-2022.pdf)).

**Confidentiality and business-model rules (HPCSA Ethical Rules)**

- Rule 13: disclose patient information only under law, court instruction or public interest, or with express consent;
  a parent or guardian consents in writing for a child under 12; next of kin or executor for a deceased patient.
- Rule 7: no commission on goods supplied, no payment for referrals, no inducements, no fee-sharing with anyone who did
  not take a commensurate part in the service, no charging for services not personally rendered. A platform fee taken as
  a percentage of consultation fees, referral fees, or pharmacy or lab kickbacks may breach it. Rules 8 and 8A limit
  practising through companies and sharing rooms with unregistered entities. Bill consultations under the
  practitioner's own practice number.
  ([HPCSA Ethical Rules](https://ethiqal.co.za/wp-content/uploads/2019/08/HPCSA-Ethical-and-Professional-Rules-of-the-Health-Profession-Council.pdf);
  rule 18 in [Werksmans](https://werksmans.com/back-to-the-future-amendment-of-rule-18-of-the-ethical-rules-leaves-practitioners-in-uncertain-territory/))

**Prescriptions**

- **Content and signature.** A prescription must be legible, handwritten, typed or made by an electronic agent, and
  signed by the prescriber in person or, if electronic, with an advanced electronic signature. It must show: prescriber
  name, qualification, council registration number and address; patient name, **ID number** and **address**; date;
  medicine name; dosage form; strength and quantity (Schedule 6 quantities in figures and words); directions and
  frequency; patient age and gender; number of repeats. The prescriber records the diagnosis, which may appear on the
  script only with the patient's consent. Pharmacists must verify authenticity. Breach of reg 33 is an offence (reg
  52(p): fine or 10 years) ([General Regulation 33](https://www.acts.co.za/medicines-/n859_33__particulars_which_mus);
  [reg 52](https://www.acts.co.za/medicines-/n859_52__offences_and_penaltie)).
- **AES.** Where a law requires a signature without naming the type, only an advanced electronic signature works for a
  data message ([ECTA s 13](https://www.acts.co.za/electronic-communications-and-transactions-act-2002/13__signature)).
  A typed name or signature image is not enough; an accredited AES bound to each prescriber is needed. The accreditation
  rules (ECTA ss 37-38) were not opened.
- **Schedules 5-8.** HPCSA rule 17(1) allows computer-generated Schedule 1-4 prescriptions under the prescriber's
  "personal and original signature"; rule 17(2) requires Schedule 5-8 prescriptions to be handwritten. This appears to
  conflict with reg 33 (2017); no amendment was found. Disable or flag e-prescribing of Schedule 5-8 medicines until
  counsel confirms ([HPCSA Ethical Rules](https://ethiqal.co.za/wp-content/uploads/2019/08/HPCSA-Ethical-and-Professional-Rules-of-the-Health-Profession-Council.pdf)).
- **Pharmacy acceptance.** The Pharmacy Council expects e-scripts to come directly from verified prescriber addresses
  or prescribing platforms; patient-forwarded scripts must be verified or declined; pharmacies guard against the same
  script being dispensed twice; pharmacists confirm the prescriber's HPCSA registration. The SAPC endorses no
  e-prescribing model ([Pharmaciae, 2026](https://pharmaciae.co.za/electronic-transmission-of-prescriptions/);
  [SAPC e-NOTE](https://www.pharmcouncil.co.za/Media/Default/Board%20Notices/Bulk%20Comm/SAPC%20e-NOTE%20REGULATORY%20OBLIGATION%20TO%20AUTHENTICATE%20A%20PRESCRIPTION-1.pdf)).
  Send scripts directly to the patient's chosen pharmacy and track dispensing status; steering patients to a pharmacy
  may raise rule 7 issues.

**Medical schemes and claims**

- A supplier must give the member an account with set particulars: member and patient names, scheme and membership
  number, practice and individual registration numbers, diagnostic (ICD-10) and item codes, dates, nature and cost of
  each service, medicine details, and the prescription where required. Claims are not late until the end of the fourth
  month; a rejected claim must be notified within 30 days with reasons; resubmission within 60 days. Failure is
  technically an offence (s 66: fine or 5 years) but in practice leads to claim rejection
  ([Medical Schemes Act s 59](https://www.acts.co.za/medical/59__charges_by_suppliers_of_service);
  [reg 5](https://www.acts.co.za/medical-/r1262_5__accounts_by_suppliers_of_services);
  [reg 6](https://www.acts.co.za/medical-/r1262_6__manner_of_payment_of_benefits);
  [s 66](https://www.acts.co.za/medical/66__offences_and_penalties)).
- **Subscriptions can be a medical scheme.** In *Epione v Council for Medical Schemes* (4 May 2026) a monthly-fee plan
  giving GP and chronic-care access from preselected practitioners was held to be the business of a medical scheme;
  without registration or exemption it cannot be offered (s 20(1); offence under s 66). Pay-per-consult and software
  subscriptions for practices are lower risk ([Deneys](https://www.deneys.co.za/thinking/news/healthcare-subscription-product-may-trigger-registration-requirement-medical-scheme)).

**Medical devices and digital health**

- SaMD rules are in section 5.
- The Health Normative Standards Framework (GN 2667 of 2022) expects health information systems, including the private
  sector, to use HL7 FHIR, IHE profiles and ICD-10; integration with national systems needs conformance testing and
  NDoH approval ([HNSF](https://www.health.gov.za/wp-content/uploads/2022/10/HNSF_Gazette_21_October_2022.pdf)). The
  National Digital Health Strategy wants a national-ID-based unique patient identifier; no successor strategy was
  published by November 2026 ([NDHS 2019-2024](https://www.health.gov.za/wp-content/uploads/2020/11/national-digital-strategy-for-south-africa-2019-2024-b.pdf)).
  The NHI Act has not been brought into operation pending a Constitutional Court judgment. Policy direction, not a
  launch blocker.

#### 3.2.4 Consumer and online terms

**Electronic Communications and Transactions Act 25 of 2002 (consumers = natural persons who are end users)**

- **Disclosures (s 43(1)).** Publish on the site or app: full legal name and status; physical address and phone;
  website and email; registration number, office bearers and place of registration; address for legal service;
  self-regulatory or accreditation bodies with contact details, and codes of conduct; a description of the service; the
  full price including taxes and fees; manner of payment; the terms and how to access, store and reproduce them; when
  the service is rendered; how to keep a record of the transaction; the return and refund policy; any ADR code; security
  and privacy policy for payments; minimum duration of recurring agreements; the s 44 cooling-off right where
  applicable. If any is missing, or the s 43(2) review step is missing, the consumer may cancel within 14 days of
  receiving the service and get every payment back
  ([ECTA s 43](https://www.acts.co.za/electronic-communications-and-transactions-act-2002/43__information_to_be_provided)).
- **Review before ordering (s 43(2)).** Let the consumer review the whole transaction, correct mistakes and withdraw
  before the order is final.
- **Secure payments (s 43(5)-(6)).** The payment system must be sufficiently secure by accepted standards; the supplier
  is liable for consumer damage if not. In practice: a PCI DSS-compliant provider with hosted or tokenised capture.
- **Cooling-off (s 44).** A consumer may cancel within 7 days of the agreement without reason, with a full refund within
  30 days. It does not apply to services that began with the consumer's consent within the 7 days (s 42(2)(d)), so
  record that consent when a consultation is booked and held inside 7 days. Terms cannot exclude these rights, whatever
  law the contract chooses (ss 47-48). Where s 44 applies, CPA s 16 does not.
- **Performance (s 46).** Perform within 30 days unless agreed otherwise; if a service becomes unavailable (a doctor
  cancels), notify immediately and refund within 30 days.
- **Formation and records (ss 13, 16, 22).** Click-accept agreements are valid; keep acceptance records accessible, in
  original or accurate form, with origin, destination, date and time.
  ([ECTA, gazetted text](https://www.gov.za/sites/default/files/gcis_document/201409/a25-02.pdf))

**Consumer Protection Act 68 of 2008** ([CPA](https://www.gov.za/sites/default/files/gcis_document/201409/321864670.pdf))

- **Plain language (s 22).** Terms, privacy notice, consent screens, refund policy, telehealth limits and AI alert
  explanations must be understandable by an ordinary consumer with average literacy.
- **Risk notices (s 49).** Terms that limit liability, shift risk, impose an indemnity or record an acknowledgement must
  be in plain language and drawn to the consumer's attention conspicuously before they agree or pay. For unusual or
  serious risks (for example "not for emergencies", "alerts are not a diagnosis") the consumer must specifically assent,
  such as by a separate checkbox, not one bundled "I agree".
- **Unfair and prohibited terms (ss 48, 51).** Terms must not be unfair; terms that waive CPA rights, exclude liability
  for gross negligence, falsely acknowledge receipt of documents, or forfeit money when CPA rights are used are void.
  Draft the liability cap with a gross-negligence carve-out. The reg 44 "grey list" was not opened.
- **Copy of the agreement (s 50(2)(b)).** Give free access to a copy in plain language with an itemised breakdown of
  the consumer's financial obligations (terms archive, fee schedule, itemised invoices).
- **Fixed-term plans (s 14).** Cancel any time on 20 business days' notice with a reasonable charge; notify 40 to 80
  business days before expiry; month-to-month afterwards.
- **Exposure.** National Consumer Tribunal fines up to the greater of 10% of annual turnover or R1 million (s 112(2)).
  Juristic consumers with turnover or assets of R2 million or more are outside the Act (GN 294 of 2011).

**Cybercrimes Act 19 of 2020**

- The s 54 duty on electronic communications service providers and financial institutions to report cybercrimes to SAPS
  within 72 hours is still not in force, and CareNexus is probably not such a provider. The unlawful-access and
  interference offences are in force and support police referral after an incident; POPIA s 22 is the operative
  breach duty ([Pagel Schulenburg](https://pagelschulenburg.co.za/cybercrimes-act-south-africa/)).

#### 3.2.5 Payments, currency and tax

- **Prices and methods.** Show prices in ZAR, VAT-inclusive, with the real payment methods (card, instant EFT, PayShap
  through a registered provider). M-PESA closed in South Africa in 2016
  ([ECTA s 43(1)(i)-(j)](https://www.acts.co.za/electronic-communications-and-transactions-act-2002/43__information_to_be_provided);
  [NextBillion](https://nextbillion.net/news/vodacom-pulls-plug-on-mobile-money-service-m-pesa-in-south-africa)).
  Confirm each chosen gateway is a SARB-registered TPPP or bank-sponsored acquirer.
- **Who may collect.** A business that regularly accepts money from payers to pay it on to third persons is a
  third-party payment provider and must be registered (National Payment System Act s 7; SARB Directive 1 of 2007).
  Either register, or make each practice the merchant of record through a registered PSP with split settlement and
  take only a platform fee ([SARB PSMB](https://www.resbank.co.za/en/home/what-we-do/payments-and-settlements/psmb)).
  NPS Act penalty amounts were not checked.
- **FICA.** CareNexus is not a Schedule 1 accountable institution just by billing patients, but s 29 applies to anyone
  carrying on a business: report to the FIC any transaction it knows or ought reasonably to suspect involves proceeds of
  crime, terrorist financing or tax evasion, or has no apparent lawful purpose (for example payment-fraud or refund
  abuse), and do not tip off the subject. Failure: up to 15 years or R100 million. If CareNexus collects as an
  intermediary, its FICA status is unsettled because draft guidance treats TPPPs as money-transfer providers
  ([FICA ss 29, 52, 68](https://www.fic.gov.za/wp-content/uploads/2023/10/Financial-Intelligence-Centre-Act-2001-Act-38-of-2001.pdf);
  [LDD](https://www.ldd.co.za/popia-payment-aggregators-south-africa/)).
- **VAT.** See 3.2.1 for thresholds. For a foreign entity, the amended Electronic Services Regulations (GN 5993, in
  force 1 April 2025) exclude services from "electronic services" only if **all** SA customers are VAT vendors, so one
  paying patient brings all SA supplies into VAT. Whether consultation fees passed through to practitioners and the
  platform fee are electronic services needs tax advice
  ([SARS VAT-REG-02-G02](https://www.sars.gov.za/wp-content/uploads/Ops/Guides/VAT-REG-02-G02-Supply-of-Electronic-Services-by-Foreign-Suppliers-and-Foreign-Intermediaries-External-Guide.pdf)).
- **Account numbers and medical aid.** POPIA s 105 on account numbers applies (3.2.2). Claims to schemes need the
  Medical Schemes Act particulars (3.2.3). The 2026 health information regulations apply if CareNexus processes for
  medical schemes or insurers ([GN 7198](https://www.gov.za/sites/default/files/gcis_document/202603/54268gon7198.pdf)).

---

## 4. Cross-border issues

### 4.1 Hosting outside both countries (for example DigitalOcean)

| | Zimbabwe | South Africa |
|---|---|---|
| Is it a transfer? | Yes: using a processor outside Zimbabwe, cloud storage reachable from outside, or remote download by staff abroad counts ([CDPG 5 paras 9.2, 9.4](https://www.veritaszim.net/sites/veritas_d/files/05%20Guideline%20on%20cross%20border%20transfer.pdf)). Hosting in SA for Zimbabwean users is also a transfer. Routing through a foreign network to a recipient inside Zimbabwe is not. | Yes for transfers to a foreign third party; the Regulator says storage counts ([Guidance Note](https://inforegulator.org.za/wp-content/uploads/2020/07/Revised-Guidance-Note-on-applications-for-Prior-Authorisation-adopted-by-Members-30-Jan14.pdf)). |
| Legal test | Recipient country or organisation must ensure adequate protection, and the transfer must be for tasks within the controller's competence; otherwise only on s 29 grounds such as unambiguous consent or contract necessity (offence for breach of s 28: level 11 or 7 years) ([CDPA ss 28-29, 33](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)). POTRAZ has published no adequacy list. | One of: binding law, BCRs or agreement giving substantially similar protection; consent; contract necessity; benefit where consent is impracticable. Usual route: a POPIA-aligned DPA with onward-transfer clauses. Breach is an interference (enforcement notice, damages), not a direct fine ([POPIA s 72](https://popia.co.za/section-72-transfers-of-personal-information-outside-republic/)). No adequacy list. |
| What to do | Notify POTRAZ and seek authorisation by letter; DPIA submitted with it; express consent naming destination country, organisation, reasons, security and storage period (only three exceptions, each still needing notice); data-sharing agreement with each recipient; inventory and periodic transfer impact assessments; regularise any existing transfers ([CDPG 5 paras 3-5, 7, 10](https://www.veritaszim.net/sites/veritas_d/files/05%20Guideline%20on%20cross%20border%20transfer.pdf)). Disclose the hosting country on Form DP1. The guideline goes beyond the Act, so whether a court would enforce the consent rule is uncertain, but POTRAZ lists transfer violations as an investigation trigger. | Self-assess adequacy. If not adequate, apply for prior authorisation for health and children's data ([POPIA s 57(1)(d)](https://popia.co.za/section-57-processing-subject-to-prior-authorisation/)). No health-sector code exists to replace it. Tell users at collection ([s 18(1)(g)](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)). Listed health bodies under the 2026 regulations must meet s 72 too. |
| Localisation? | No designation under s 28(3) found, so no explicit localisation rule today; future records-storage regulations under MSA s 16(2)(e8) could add one. | None for health data found. |

Practical options, from least to most regulatory friction (assessment, not legal advice): (a) host in South Africa
(major clouds have SA regions; DigitalOcean's region list should be checked) and self-host LiveKit there, treating
SA-to-ZW as the only transfer; (b) host in an EU/GDPR region with binding DPAs, file the ZW notifications and the SA prior
authorisation as a precaution; (c) host elsewhere and rely on consent plus authorisations in both countries.

### 4.2 Doctors in one country treating patients in the other

- **SA doctor, ZW patient.** Zimbabwe registration is required to practise medicine on a patient in Zimbabwe. The only
  exemptions are for a non-resident doctor called in as a consultant by a Zimbabwe-registered practitioner for a
  particular patient, or called into Zimbabwe by the patient; their prescriptions then have full effect. They were
  written for physical attendance, and remote use is untested
  ([HPA ss 121, 129](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf)).
  Safest pattern: a Zimbabwe-registered treating doctor invites the SA specialist, and the platform logs who invited
  whom. Fees for unregistered practice are unrecoverable (s 118).
- **ZW doctor, SA patient.** The doctor must be registered with the MDPCZ and with the HPCSA before the consultation;
  where two practitioners collaborate, both must be HPCSA-registered
  ([Booklet 10 para 5.1(c)-(d)](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf);
  [HPA 56 of 1974 s 17](https://www.acts.co.za/health/17__registration_a_prerequisite_for_practising)). There is no
  invited-consultant exemption like Zimbabwe's s 129.
- **Product consequence.** Store the patient's country of residence, capture the patient's location at the start of
  each consultation, store each doctor's registrations per country with expiry, and only offer doctors registered in the
  patient's country (or via the invited-specialist flow in Zimbabwe).
- **Money between the countries.** A Zimbabwean entity earning from SA users must repatriate within 90 days and
  surrender 30% at the interbank rate ([FXD5 s 3.2](https://www.rbz.co.zw/documents/Regulations_Acts/2026/Foreign_Exchange_Guidelines_FXD5_version_2._March_2026.pdf));
  a foreign entity selling e-services to SA consumers may need SA VAT registration (3.2.1). Group structure decides both.

### 4.3 Sub-processors

Every vendor that touches personal data needs a written processing contract with security terms and a breach-notice
duty fast enough for Zimbabwe's 24-hour deadline, and is a cross-border transfer if outside the patient's country
([CDPA s 18(4)-(5)](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf);
[SI 155 reg 10(4)(f)](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf);
[POPIA ss 20-21, 72](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)).
Integrations also need documented authority, because connecting a record system to another without authority is an
offence in both countries ([MSAA s 8H(2)(h)](https://becomingthemuse.net/wp-content/uploads/2026/07/medical-services-amendment-act-number-3-of-2026.pdf);
[NHA s 17(2)](https://www.acts.co.za/national-health/17__protection_of_health_records)).

US vendors' standard terms do not cover either country. LiveKit's project data region is fixed at creation (US by
default, or EU Frankfurt); no African region exists; region pinning of realtime traffic needs the Scale plan and LiveKit's
help and does not control storage; self-serve customers accept the GDPR/SCC DPA through the click-through terms
([LiveKit](https://docs.livekit.io/deploy/admin/regions/data-residency)). Twilio's DPA (version of 9 April 2026, read in
the fact-check) relies on the EU-US Data Privacy Framework, BCRs (not covering SendGrid) and EU SCCs, and its definition
of applicable law leaves out South Africa and Zimbabwe. So add POPIA and CDPA riders to every DPA, and list every vendor
in the POTRAZ transfer notification. If a Zimbabwean entity pays these vendors, register recurring agreements with the
RBZ and expect 15.5% digital-services withholding on payments to vendors not VAT-registered in Zimbabwe
([FXD5 s 3.1.5](https://www.rbz.co.zw/documents/Regulations_Acts/2026/Foreign_Exchange_Guidelines_FXD5_version_2._March_2026.pdf);
[KPMG](https://kpmg.com/us/en/taxnewsflash/news/2026/01/tnf-zimbabwe-digital-services-withholding-in-lieu-of-nonresident-vat-collection.html)).

| Vendor (planned seam) | Data it sees | Notes |
|---|---|---|
| Hosting and managed PostgreSQL | everything | Choose region first (4.1). Back up to ISO 27799-type standards ([Booklet 9, 2022, para 6.4](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/11/Booklet-9-Keeping-of-Patient-Records_Review-Draft_-FINAL_Sept-2022.pdf)). |
| LiveKit (`calls.provider.LiveKitCallProvider`; URL is configurable, so cloud or self-hosted) | live audio and video, names, call metadata | Pick the data region before creating a Cloud project, or self-host in an SA region. Recording is not implemented; keep it off (a recording needs express consent and becomes part of the record, [MDPCZ policy Box 7](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf); [Booklet 10 para 5.7](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf)). |
| Email (`delivery.email.EmailSender`), SMS (`delivery.sms.SmsSender`), push (`delivery.push.PushSender`) | names, contact details, notification text | Text is generic today, except dose reminders that name the medication. Remove the medication name from SMS (confidentiality, [Public Health Act s 39](https://bwcimplementation.org/sites/default/files/resource/ZW_Public%20Health%20Act%202018_EN.pdf); [NHA s 14](https://www.acts.co.za/national-health/14__confidentiality)). |
| Object storage and virus scanning (`storage.FileStorage`, `storage.FileScanner`) | uploaded clinical documents | Same region as the database. |
| Payment gateway and mobile money (`billing.provider.PaymentGateway`; only the built-in local gateway exists) | payer identity, amounts, phone numbers | Tokenise (POPIA s 105); PCI DSS provider (ECTA s 43(5)); registered TPPP or practice-as-merchant design (3.1.5, 3.2.5). |
| Insurance claims (`billing.provider.InsuranceClaimProvider`; manual provider only) | diagnoses, invoices | Written consent for disclosure to insurers (Public Health Act s 39, NHA s 14); 2026 SA health regulations; Medical Schemes Act particulars. |
| AI provider (`ai.provider.RiskDetectionProvider`) | symptoms, readings | Section 5. |
| E-signature (AES) provider, pharmacy network (not built) | prescriber identity, prescriptions | Needed for SA e-prescriptions (3.2.3). |
| Licence registry, captcha, wearables | identity, device data | Captcha providers often set cookies (cookie notice). |

### 4.4 Records retention

| Data | Zimbabwe | South Africa | Notes |
|---|---|---|---|
| Clinical records | Doctors: 10 years from last entry; institutions: indefinitely or at least 10 years, then National Archives ([MDPCZ policy s 10](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf)) | Ideally indefinitely; otherwise at least 6 years from dormancy; minors until 21; incapacitated patients for life; long-latency exposures 25 years ([Booklet 9, 2022, para 7](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/11/Booklet-9-Keeping-of-Patient-Records_Review-Draft_-FINAL_Sept-2022.pdf)) | Simplest compliant rule for a two-country platform: keep clinical records indefinitely unless a policy decision says otherwise; never on account closure. |
| Telehealth messages and consent records | Part of the record (WhatsApp chats are not records) ([Techzim](https://www.techzim.co.zw/2022/10/what-you-should-know-about-the-recently-published-mdpcz-policy-on-telemedicine/)) | Filed in the medical record ([Booklet 10 paras 5.8(g), 5.9.1](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf)) | Treat clinical messages like clinical records. |
| Prescriptions and dispensing | Dispensers keep records 5 years ([S.I. 150 ss 63-64](https://mdpcz.co.zw/external_policies/1682978281Medicines%20and%20Allied%20Substances%20Control%20General%20Regulations%201991SI%20150%20of%201991.pdf)) | Permanent copy of electronically sent scripts ([reg 33(5)](https://www.acts.co.za/medicines-/n859_33__particulars_which_mus)) | Prescriptions are part of the clinical record anyway. |
| Billing | Not specified (tax law; not researched) | Keep separate from clinical records ([Booklet 9, 2016, para 13.7](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-9-Guidelines-on-Patient-Records.pdf)) | Tax retention periods not researched. |
| Other personal data | No longer than necessary; offence to breach ([CDPA s 13](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)) | No longer than necessary; long enough for a person to request access to a record used for a decision; then destroy or de-identify ([POPIA s 14](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf)) | Documented schedule per category. |
| Destruction | Secure; contractor certificate; destruction register ([MDPCZ policy s 11](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf)) | Unauthorised destruction is an offence ([NHA s 17(2)](https://www.acts.co.za/national-health/17__protection_of_health_records)) | Disposal register and legal holds. |

---

## 5. AI risk alerts

**What CareNexus does today.** `rules-v1` (`ai.provider.RuleBasedRiskDetectionProvider`) screens symptoms and readings;
alert generators raise critical-vitals, anomaly, critical-result, missed-dose and medication-interaction alerts
(`ai.generator.*`). Diagnostic-type detections start as `PENDING_REVIEW` and reach the patient and caregivers only
when a doctor marks them `CONFIRMED` (`ai.detection.model.DetectionReviewStatus`, `ai.alert.model.AiAlertType`). Each
detection stores a `model_version` (`ai.detection.model.DiseaseDetection`). There is no patient opt-out from automated
screening. The human-in-the-loop design is the right starting point.

**Automated decisions.** Both countries give people the right not to be subject to a decision based solely on
automated processing that has legal or significant effects (Zimbabwe's reg 10(3) covers legal effects only, and the
Act also never allows it for children's data). The person must be able to make representations and get information
about the logic ([CDPA s 25](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf);
[SI 155 reg 10(3), 10(5)(f)](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf);
[POPIA s 71](https://popia.co.za/section-71-automated-decision-making/)). No regulator has said whether
clinician-reviewed advisory alerts count as "solely automated". SA's Information Regulator had issued no AI guidance by
March 2026, and the draft national AI policy was withdrawn in April 2026
([ITLawCo](https://itlawco.com/information-regulator-2026-27-annual-performance-plan/)).

**HPCSA Booklet 20 (AI, September 2025).** Binding on SA practitioners, not on CareNexus, but the platform decides
whether they can comply: practitioners always make the final decision and AI may not be the final decision-maker
(paras 3.5, 9.1-9.2); tell patients when AI determined or assisted a diagnosis or treatment recommendation, how, and its
biases (6.2); disclose capabilities, limitations and bias safeguards before use (6.7); say whether the model is locked
or continuously learning (4.3-4.4); patients who decline AI must not be disadvantaged or refused services (6.2); use only
AI validated for reliability and cultural appropriateness (4.7); AI in a device must meet SAHPRA rules (4.6);
developers are "also liable with the user" for harm from malfunction or inaccurate output (5.5), which matters for
contracts and insurance ([Booklet 20](https://www.hpcsa-blogs.co.za/wp-content/uploads/2025/11/ETHICAL-GUIDELINES-USE-OF-AI.pdf)).

**Medical-device status.**

- **South Africa.** Software intended for diagnosis, prevention, monitoring or treatment is a medical device; SAHPRA
  names predictive algorithms that forecast deterioration risk and clinical decision support as in scope, and treats
  generative-AI symptom checkers and note summarisers by intended use. Anyone manufacturing, importing or marketing one
  needs a s 22C establishment licence with device listing (product registration has not been called up). The
  manufacturer classifies the device (Class A-D under SAHPGL-MD-04; decision-driving software is likely C or D, needing
  prior approval from a reference regulator such as FDA, CE, TGA or WHO PQ), runs an ISO 13485 QMS with IEC 62304 and
  ISO 14971, keeps a technical file and clinical evidence including local-population performance, reports incidents
  (SAHPGL-MD-03), and agrees a change control plan with SAHPRA where updates are expected. Formal AI pathways are "yet
  to be developed". Whether a purely rule-based threshold alert is a device depends on its intended purpose and claims
  ([SAHPRA MD08](https://www.sahpra.org.za/wp-content/uploads/2025/09/MD08-20252026_-SAHPRA-Communication-to-Industry-AI-Medical-devices_Acknowledgements.pdf);
  [Medicines Act s 22C](https://www.acts.co.za/medicines-/22c__licensing)). A notice exempting some Class A
  manufacturers (Notice 5855 of 2025) exists but was not read (unverified).
- **Zimbabwe.** No device or SaMD rules exist yet ([MCAZ](https://www.mcaz.co.zw/documents/medical-devices-docs/regulations-and-guidelines-docs/)).

**So:**

- Write a narrow intended-use statement for the alerts ("decision support for the treating clinician; not a diagnosis,
  not monitoring, not an emergency service") and keep UI, app store and marketing copy consistent with it. Get a
  SAHPRA classification opinion before marketing alerts as clinical risk prediction in SA or adding an AI model.
- Keep alerts advisory; never let them change triage, eligibility, billing or care without a clinician; log
  alert-to-decision.
- Label alerts as automated decision support, show the rule or rationale and its known limits ("why am I seeing
  this"), offer a way to challenge an alert, and explain the logic in the privacy notice.
- Offer a patient-level "no automated screening" preference that does not reduce access to care (Booklet 20 para 6.2).
- Keep `model_version` on every detection, version-lock rules and models, and document validation on Zimbabwean and
  South African populations.
- For under-18s, keep every alert behind clinician review and do not automate any decision.
- Before switching to an external AI provider: notify POTRAZ of the new processing and the transfer (possibly prior
  authorisation as high-risk new technology, [CDPA s 22](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)),
  run a DPIA, sign a DPA that forbids training on CareNexus data, and do model training only on de-identified data or
  with ethics approval and consent ([Public Health Act s 40](https://faolex.fao.org/docs/pdf/zim190656.pdf);
  [NHA s 16](https://www.acts.co.za/national-health/16__access_to_health_records_by_health_care_provider)). An external
  model raises the SAHPRA risk class.
- The deterministic rules, reference ranges and interaction table also need the clinical safety review already listed
  in TODO.md section 3.

---

## 6. What CareNexus already supports, and the gaps

### 6.1 Already in place

| Capability | Where | How it helps |
|---|---|---|
| Versioned terms and privacy acceptance (version, time, IP, user agent; append-only); admins publish new versions, users re-accept at next sign-in | `account/legal` (`LegalAcceptance`, `LegalService`, `/api/users/me/legal/accept`, admin **Legal documents**) | Evidence of consent (CDPA s 11, POPIA s 11(2)(a)) and of contract formation (ECTA ss 16, 22). Can be extended to new consent types. |
| Caregiver consent ledger: immutable row per grant step with a scope snapshot, expiry and revocation; patient view of consents | `caregiveraccess` (`ConsentRecord`, `ConsentLedgerService`, `GET /api/patients/me/consents`) | Proof of patient-authorised sharing (Public Health Act s 39, NHA s 14, POPIA). |
| Scoped caregiver permissions; messaging and calls need the patient's own approval, re-checked when a call token is issued; expiry jobs | `core.model.DependentAccess`, `common.service.PatientPermission`, `calls.service.CallService`, `caregiveraccess/job` | Least-privilege proxy access; supports Booklet 10 para 5.7(f) on others present in a call. |
| Patient can see what a caregiver did with their data | `GET /api/patients/me/caregiver-access/{accessId}/activity` | Partly meets POPIA s 23(1)(b). |
| Doctors reach a patient only through a care relationship (care team, appointment or consultation) | `common.service.AuthorizationService.requirePatientAccess` | Aligns with Public Health Act s 40 and NHA s 16. |
| Audit of every read and write of patient data, plus denials | `common.service.AuditService`, `audit_log`, admin **Audit log**; `docs/api/CONTRACT.md` section 8 | Accountability evidence (CDPA s 24, POPIA s 19), breach investigation, NHA s 17 controls. |
| Self-service data export (JSON bundle, 7-day download, 3 per day) | `account/datarights` (`POST /api/users/me/data-export`, `DataExportBundleBuilder`) | Access right (CDPA s 14, POPIA s 23). |
| Account closure (clinical records kept) and deletion requests decided by an admin, which anonymise the account but keep clinical rows | `AccountDataRightsService`, `AccountRequestAdminService` | Matches the need to retain clinical records while honouring deletion where lawful. |
| Patient correction requests for clinical records | `corrections` (`record_correction_requests`) | Correction right (CDPA s 14, POPIA s 24). |
| Retention sweeper for operational data only; clinical, audit, consent, legal and messaging rows never purged | `account/retention/job/RetentionSweeper`, README "Retention policy" | Base for a retention schedule; telehealth messages survive (Booklet 10 para 5.8(g)). |
| Clinical rows soft-deleted only; prescription medication, dose, route and quantity immutable (cancel and reissue) | `docs/architecture/DATA-MODEL-NOTES.md` section 1; `PrescriptionController` | Partly meets MDPCZ and Booklet 9 "never erase" rules. |
| TOTP MFA (required for admins outside development, configurable per role), encrypted secrets, sign-in throttling, rate limits, new-sign-in alerts | `account/mfa`, `auth/service/LoginAttemptThrottle`, `common/ratelimit`, `account/sessions` | Security measures (CDPA s 18, POPIA s 19, Booklet 10 para 5.9.3). |
| Notification preferences per category and channel, quiet hours; generic notification text without results, diagnoses or doses | `communications` publisher, `user_settings`; CONTRACT section 10 | Confidentiality of SMS and email; separates transactional messages. |
| No card numbers stored (Luhn validator on free text); saved methods hold a provider token and last four digits | `common/validation/NoCardNumber`, `billing.model.PaymentMethod` | Supports POPIA s 105, ECTA s 43(5) and PCI scope. |
| Consultation fee shown in the booking summary before booking; amount shown before "Pay now" | `frontend/src/components/web/appointments/booking-form.tsx`, `components/web/billing-actions/pay-dialog.tsx` | Partly meets cost disclosure (PHA s 34, Booklet 10 para 5.7(c)) and the ECTA s 43(2) / ZW CPA s 52(2) review step. |
| Clinician licence capture (number, licensing body, ISO country) and an admin approval queue; registry seam | `core.model.Doctor`, `admin/clinician` (`LicenseRegistryClient`) | Start of practitioner verification. |
| Guardian attestation recorded for managed dependents | `CaregiverService`, `dependent_access.guardian_attested_at` | Start of the minors flow (attestation only). |
| Upload scanning seam (type allow-list, virus scanner interface) | `storage` | Security of uploaded records. |
| Logs never carry tokens, message bodies or clinical values; signed webhooks; startup refuses dev secrets | README "Observability", "Environments and profiles" | Security hygiene. |
| AI detections need doctor confirmation before patients see them; each detection stores its `model_version` | `ai.detection` | Human in the loop (CDPA s 25, POPIA s 71, Booklet 20 paras 9.1-9.2); version logging. |
| No video recording; LiveKit URL configurable (self-hosting possible) | `calls`, `.env.example` | Avoids recording consent and retention issues; allows an SA-hosted media server. |
| ICD codes on medical records | `core.model.MedicalRecord`, `terminology` | Start of claims coding and HNSF alignment. |

### 6.2 Gap table

Rows marked **(new)** were added in this revision from the health-sector, consumer, payments and hosting research;
rows marked **(updated)** were extended.

| Requirement | Country | Current state | Change needed (P = product, D = document, B = business process) | Priority |
|---|---|---|---|---|
| POTRAZ data controller licence, renewed yearly | ZW | None | B: apply on DP1 with hosting country disclosed; calendar renewal 3 months before expiry | Before launch |
| Certified DPO, Form DP2, contact published | ZW | None | B: appoint and certify; P: show DPO and Information Officer contacts in the privacy notice and Help (a system setting like the support contact) | Before launch |
| Information Officer registered on eServices | ZA | None | B: register CEO or delegate and deputies | Before launch |
| Legal entity and role (controller or processor; ZW representative; merchant of record) (updated) | Both | Undecided | B: decide contracting entity per country, controller/operator role and who collects consultation fees; reflect in terms | Before launch |
| Explicit, separate, withdrawable consent for health and other sensitive data | Both | Only TERMS and PRIVACY acceptance types (`LegalDocument.TYPES`); no withdrawal | P: add consent document types (health data processing, cross-border transfer, telehealth) to `account/legal`, record them at onboarding (web and native), add a withdraw action and define its effect (stop processing, close account) | Before launch |
| Cross-border transfer approvals and disclosure | Both | Hosting not chosen; no transfer notice or consent | B: choose region; file POTRAZ notice, DPIA and authorisation letter; assess SA adequacy or apply for prior authorisation; P: transfer disclosure and consent screen | Before launch |
| Video region (new) | Both | `LIVEKIT_URL` configurable; no region chosen; LiveKit Cloud has only US (default) and EU regions, fixed at project creation | B: decide self-hosted LiveKit in an SA region or a Cloud project created in the chosen region; keep recording and agent features off | Before launch |
| Record of processing and POTRAZ notification of processing | ZW | None | D: record of processing for every module and vendor; B: submit with DP1 | Before launch |
| DPIA / PIIA | Both | None | D: DPIA covering health data, minors, transfers, AI alerts, video | Before launch |
| Privacy notice meeting CDPA s 15 and POPIA s 18 | Both | V29 placeholder text | D: write per-country notices (section 7); publish through admin **Legal documents** | Before launch |
| PAIA manual | ZA | None | D: write and publish; P: footer link on web and mobile | Before launch |
| Processor and operator agreements (updated) | Both | None | B/D: DPAs with hosting, LiveKit, email, SMS, push, storage, payments, claims, AI, captcha, each with POPIA and CDPA riders (vendor DPAs name neither country) and breach notice in hours | Before launch |
| Breach response (ZW 24 h to POTRAZ, 72 h to people; SA eServices) | Both | Audit log and monitoring exist; no runbook or register | D: incident response plan, DP3 and SA templates; B: on-call; P (soon after): admin breach register | Before launch |
| Security evidence: risk assessment, penetration test, tested backup restore | Both | CI scans (OWASP, gitleaks, npm audit); backups and restore planned in TODO | B/D: risk assessment, external pen test, restore test, security policy | Before launch |
| Admin access to clinical content | Both (explicit in ZW PHA s 40, MSAA s 8H, SA NHA s 17) | `requirePatientAccess` allows ADMIN on all patient data; admins can update medical records | P: remove admin read and write of clinical content, or add break-glass with a reason, alert to the patient's doctor and audit; remove admin edit of medical records | Before launch |
| Append-only medical records with visible amendment history | Both | `PUT /api/medical-records/{id}` and consultation edits overwrite in place (audit row only); prescription refills, expiry and instructions edited in place (`PUT /api/prescriptions/{id}`) | P: versioned record entries (author, timestamp, reason, retrospective flag), amendment history shown to clinicians; treat prescription instruction changes as discontinue and reissue (needs a migration) | Before launch |
| Clinician registration per patient country, practising certificate expiry (updated) | Both | One licence number, body and country per doctor (`Doctor.license_no`, `licensing_body`, `license_country`); manual approval; no expiry; no practice number; no matching to patient country | P: store registrations per country (MDPCZ, HPCSA) with expiry and practice number, block or suspend on expiry, show only doctors registered in the patient's country; B: verification procedure with MDPCZ and HPCSA, periodic re-check | Before launch |
| Patient country of residence | Both | Not captured (clinic zone `Africa/Harare` only; `Patient` has no country or address) | P: country on patient profile; drives doctor matching, consent wording, currency and applicable notice | Before launch |
| Patient location and identity check at each consultation; triage (new) | ZA (good practice ZW) | No location captured at booking or call start; no triage step | P: confirm patient location and identity when a consultation starts, record it; triage questions at booking that route red flags to emergency guidance | Before launch |
| Cross-border specialist consult | ZW | Not supported | P: "specialist requested by treating doctor" flow logging the inviting Zimbabwe-registered doctor (HPA s 129) | Soon after |
| Telehealth consent and cost disclosure before a consultation (updated) | Both | Fee shown in the booking summary; no telehealth consent | P: telehealth consent per patient and practitioner, recorded and downloadable, with Booklet 10 para 5.7 contents (practice number, security, others present, recording policy, cost); shown before the first video consult with each practitioner | Before launch |
| No prescribing without a documented evaluation (new) | ZA (good practice ZW) | Prescriptions can be created without a consultation (`consultationId` optional) | P: for SA patients require a linked consultation with an evaluation note before a prescription; add an "escalate to in-person" action | Before launch |
| SA e-prescription validity (new) | ZA | No signature of any kind; no schedule classification; no patient ID number or address; quantity free text; refills 0-99 | P: AES integration with an accredited provider; reg 33 fields (prescriber qualification, registration number, address; patient ID number, address, age, gender; repeats; Schedule 6 quantity in words); diagnosis on script only with consent; block or flag Schedule 5-8 medicines; immutable once signed | Before launch (if prescribing in SA) |
| ZW prescription rules (new) | ZW | Refills 0-99; expiry optional (none = no expiry); no prescriber qualifications or address, no patient address; no "dispense once only" flag | P: required s 74 fields; cap supply at 30 days and 5 repeats; first-dispense validity 3 months; specially restricted flag with single dispense and countersignature workflow; B: confirm pharmacies accept e-scripts | Before launch (if prescribing in ZW) |
| Prescription delivery and dispensing status (new) | Both | No pharmacy routing or dispensed status | P: send scripts directly to the patient's chosen pharmacy, verification lookup for pharmacies, mark dispensed or partly dispensed; patient copy marked "not to be dispensed" | Soon after |
| Emergency and no-doctor fallback | Both | None (README forbids emergency-monitoring claims) | P: show emergency numbers and nearest public facility guidance when booking, in calls and in alerts; written emergency instructions (Booklet 10 para 4.3) | Before launch |
| Minors: age gate and guardian verification | Both | Date of birth optional; no age logic; guardian self-attestation only | P: required date of birth, under-18 path needing a guardian with uploaded proof (birth certificate or order) reviewed by an admin, no automated decisions, re-consent at 18; B: POTRAZ authorisation for offshore minors' data | Before launch |
| Adolescent record access (new) | ZA | No age logic; caregiver proxy sees what the scope allows | P: let patients 12+ hold their own access; require the adolescent's consent before a parent proxy sees records (Booklet 9, rule 13); B: legal view on POPIA 18 versus HPCSA 12/16 | Soon after |
| Billing currency and local payment methods (updated) | Both | Default currency KES (`Invoice.DEFAULT_CURRENCY`, `billing.default-currency`); methods CARD, INSURANCE, CASH, MPESA, BANK_TRANSFER; M-PESA phone stored as typed (`mpesa_phone`); currency per invoice but no exchange-rate record | P: currency per country (USD and ZiG in ZW, ZAR in SA), exchange rate stored on any converted invoice, refunds in the paying currency; EcoCash, OneMoney, InnBucks, Zimswitch via an aggregator, SA card, instant EFT, PayShap; store provider tokens rather than raw mobile-money numbers; remove M-PESA | Before launch (if billing is live) |
| Who collects consultation fees (new) | Both | One gateway seam; only the built-in local gateway; no payouts to doctors | B: either register as TPPP with the SARB and use a recognised ZW participant, or make each practice the merchant of record with split settlement (or a formal ZW collection-agent appointment); no wallet or stored balance; P: build the chosen settlement model | Before launch (if billing is live) |
| Tax on fees and VAT registration (new) | Both | One tax rate per deployment (`billing.tax-rate`, default 0) applied to the whole invoice | B: tax advice on exempt medical fees versus taxable platform fees; VAT registration at R2.3m (SA) or US$25,000 (ZW); P: tax per line item, VAT-inclusive display, fiscalised invoices in ZW if registered | Before launch (if billing is live) |
| ZW payment taxes and FX rules (new) | ZW | Not modelled | B: model IMTT (1.5% ZiG, 2% USD) and digital-services withholding in fees and reconciliation; register recurring offshore vendor agreements with the RBZ if a ZW entity pays; plan 30% export surrender if a ZW entity earns from SA | Before launch (B) |
| No billing for unregistered clinicians | ZW | Invoices created after consultations (default DRAFT for the doctor to send) | P: block auto-invoice unless the doctor's Zimbabwe registration is verified and current | Before launch (if billing is live) |
| HPCSA fee-sharing and employment rules (new) | ZA | Fees set per doctor (`FeeSchedule`); invoices carry the doctor but no practice number; no platform fee | B: structure platform fees as a flat technology fee, not a share of consultation fees; no referral payments or pharmacy steering; HPCSA employer approval if doctors are employed; P: invoice under the practitioner's practice number | Before launch |
| Supplier disclosures page (new) | Both | Terms and privacy pages only (server-managed placeholder text); Help page shows clinic name and support contact | D/P: legal information page with ECTA s 43 / ZW CPA s 52 items (legal name, registration number, address, office bearers, legal service address, price and payment methods, refund policy, cooling-off, security policy), linked from booking and checkout | Before launch |
| Order review, cancellation and refund policy (new) | Both | Booking summary shows the fee; pay dialog shows the amount before "Pay now"; pay after consultation; refunds by admins only | D: refund and cancellation policy (SA: 7-day cooling-off with recorded consent to early performance; ZW: 7-day cancellation with no performed-service exclusion; no "no refund" wording; refund within 30 days of a doctor cancelling a prepaid consult); P: record consent to early performance at booking, make the review step allow edit and cancel before any prepayment | Before launch |
| Conspicuous risk notices and fair terms (new) | Both | Single terms and privacy acceptance | D: terms reviewed against CPA ss 48-51 and ZW CPA ss 41-43 (gross-negligence carve-out, no rights waivers); P: separate acknowledgements for "not an emergency service", "alerts are not a diagnosis" and telehealth limits, before booking or enabling alerts | Before launch |
| UI and marketing copy must not imply CareNexus is a registered practice | ZW | `CLINIC_NAME` shown in app; README forbids compliance claims | D/P: copy review; describe as a platform | Before launch |
| Clinical detail in SMS and email | Both | Generic text; dose reminders name the medication | P: drop the medication name from SMS (or make it opt-in) | Before launch |
| Retention schedule and disposal register (updated) | Both | Clinical, audit and messaging data kept indefinitely; operational retention configured | D: schedule per data category (section 4.4: clinical at least 10 years ZW and 6 years from dormancy SA, minors to 21, incapacitated for life, or simply indefinitely; billing kept separately; audit; exports; closed accounts); P (later): legal-hold flag, disposal register when anything clinical is destroyed | D before launch; P later |
| Complaints procedure (new) | ZW (PHA s 41, MSA s 8I) | Support threads exist (`CreateSupportThreadRequest`); no complaint type, acknowledgement or escalation | P: complaint category with acknowledgement receipt, tracking and referral to MDPCZ or HPA; D: displayed complaints procedure | Soon after |
| Patient view of who accessed their record | ZA | Export includes audit rows where the patient is the actor; caregiver activity visible; doctor and admin access not shown | P: "who accessed my record" view or export section built from `audit_log` by patient | Soon after |
| Objection requests, free multi-channel requests, 30-day access SLA | Both | Export, closure, deletion and correction self-service; support conversations; no objection type, no SLA tracking | P: objection request type and due dates in the admin **Account requests** queue; D: request-handling procedure (email, phone, WhatsApp, fax intake; telephone requests recorded) | Soon after |
| Incapacitated adult proxies | ZW | Caregiver attestation only | P/B: record legal authority (court order, written mandate) and physician certification before full proxy rights | Soon after |
| Notice to patients whose data a caregiver entered | ZW | None | P: notify the patient (or show at first sign-in) what a caregiver added | Soon after |
| Languages (Shona, Ndebele) for consent and key information | ZW | English only (language setting removed) | P: translate consent screens and key notices | Soon after |
| AI transparency and challenge route (updated) | Both | Doctor review exists; `model_version` stored; no explanation to patients | D: explain logic and limits in privacy notice and an AI disclosure (Booklet 20 paras 6.2, 6.7); P: label alerts as decision support, "why am I seeing this" text and a dispute action on alerts | Before launch (notice); soon after (UI) |
| Patient opt-out of automated screening (new) | ZA (good practice ZW) | No opt-out | P: per-patient "no automated screening" setting with no loss of access to care | Soon after |
| AI medical-device classification (updated) | Both | Intended use not documented; SA rules now verified (SAHPRA MD08); ZW has none | D: intended-use statement; B: SAHPRA classification opinion before marketing alerts as risk prediction or adding a model; s 22C licence and QMS if in scope | Before launch (intended use); soon after (opinion) |
| External AI provider controls | Both | Seam only | B/D: POTRAZ notice or authorisation, DPIA, DPA with no-training clause, de-identification or ethics approval | Later (before enabling) |
| Suspicious transaction escalation (new) | ZA | None | B: internal FICA s 29 escalation path and goAML registration plan for payment, claims and refund fraud | Soon after (if billing is live) |
| Medical scheme claim particulars (new) | ZA | Claims hold insurer, policy and member number, amount and documents; manual provider; no ICD-10 on claims, no practice number, tariff codes or deadline tracking | P: add practice and registration numbers, ICD-10 and tariff codes, 4-month submission and 30/60-day rejection tracking, attach prescription for medicine claims | Later (when claims go live) |
| Subscription or prepaid plans (new) | ZA (CPA s 23 in ZW) | No subscription feature found | B: legal assessment against Medical Schemes Act s 20 (Epione) before any consumer plan; CPA s 14 / ZW CPA s 23 renewal notices if fixed-term | Later (before offering plans) |
| Direct marketing opt-in (updated) | Both | No marketing channel | P: keep marketing out of notification categories; before any marketing add SA opt-in (Form 4 wording, asked once), ZW objection and unsubscribe, opt-out in every message, per-channel consent records | Later |
| Interoperability (new) | Both | ICD codes on records; no FHIR export; no national ID | P: model exports on FHIR R4 and store an optional national ID or passport number for future HPRS/Impilo matching (needs POPIA s 57(1)(a) authorisation before linking) | Later |
| Video recording | Both | Not implemented | None now; if added: express consent, store in the record, retention | Later |
| Cookie notice | Both | Web uses `sessionStorage`; no third-party analytics found | D: short cookie and storage notice; revisit when captcha or analytics are added | Before launch (short) |
| SA health information regulations (insurers, schemes, employers) | ZA | Insurance claims handled manually | B: assess when claims integration or employer wellness deals start | Later |
| Linking patient IDs with labs, pharmacies or insurers | ZA | Not done | B: POPIA s 57(1)(a) prior authorisation before such linking | Later |
| Unique patient identifier (CDPA s 12(8)) | ZW | Internal `PAT-` ids | B: legal question (section 8) | Before launch (ask) |

---

## 7. Documents to produce

| Document | Country | Must contain |
|---|---|---|
| Privacy notice (per country, or one with country sections) | Both | Controller identity and address; DPO (ZW) and Information Officer (SA) contacts; purposes per module; lawful basis per activity; categories of data, with sensitive and health data explained; whether each field is mandatory and the consequence of not giving it; recipients and sub-processors; hosting location and transfers with the protection level; retention per category; rights (access, correction, deletion, objection, withdraw consent, complain to POTRAZ or the Information Regulator with contact details) and how to exercise them free of charge by email, phone, WhatsApp, fax, post; caregiver access; children; AI alerts and their logic; security summary. Plain language ([CPA s 22](https://www.gov.za/sites/default/files/gcis_document/201409/321864670.pdf)). Sources: [CDPA s 15](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf), [POPIA s 18](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf). |
| Terms of use | Both | Contracting entity per country; CareNexus as a platform, not a clinic or medical practice ([HPA s 127](https://disasterlaw.ifrc.org/sites/default/files/media/disaster_law/2021-08/0-1526552232HEALTH%20PROFESSIONS%20ACT.pdf)); doctors are independent registered practitioners responsible for care; not an emergency service, with emergency numbers; eligibility and minors; caregiver rules; fees, payment, refunds, currency; confidentiality undertakings; acceptable use; liability within consumer law limits, with a gross-negligence carve-out and no rights waivers ([CPA ss 48-51](https://www.gov.za/sites/default/files/gcis_document/201409/321864670.pdf); [ZW CPA ss 41-43](https://commons.laws.africa/akn/zw/act/2019/5/eng@2019-12-10.pdf)); risk terms highlighted with separate assent (CPA s 49); ECTA Chapter VII rights cannot be excluded whatever law governs ([ECTA ss 47-48](https://www.gov.za/sites/default/files/gcis_document/201409/a25-02.pdf)); governing law per country; change process (re-acceptance is already built); free downloadable copy (CPA s 50(2)(b)). |
| Supplier information page | Both | Every [ECTA s 43(1)](https://www.acts.co.za/electronic-communications-and-transactions-act-2002/43__information_to_be_provided) and [ZW CPA s 52(1)](https://commons.laws.africa/akn/zw/act/2019/5/eng@2019-12-10.pdf) item: legal name and status, registration number, office bearers, physical address, phone, email, legal service address, accreditation bodies, service description, prices with taxes in local currency, payment methods, refund policy, cooling-off, payment security and privacy policy. |
| Refund and cancellation policy | Both | SA 7-day cooling-off and the early-performance exclusion; ZW 7-day cancellation and 14-day refund with no performed-service exclusion; refund within 30 days when a doctor cancels; currency of refunds; no "no refund" wording ([ECTA ss 44, 46](https://www.gov.za/sites/default/files/gcis_document/201409/a25-02.pdf); [ZW CPA ss 42, 53](https://commons.laws.africa/akn/zw/act/2019/5/eng@2019-12-10.pdf)). |
| Health data and cross-border consent text | Both | Separate, specific consent for health processing; destination country, organisation, reasons, security and storage period for transfers ([CDPG 5 para 3.2](https://www.veritaszim.net/sites/veritas_d/files/05%20Guideline%20on%20cross%20border%20transfer.pdf)); written consent for disclosure to caregivers and insurers ([NHA s 14](https://www.acts.co.za/national-health/14__confidentiality)); how to withdraw and what happens. |
| Telehealth consent form | Both | Patient and practitioner identities and practice number; that the practitioner decides whether telehealth is appropriate; types of transmission; security measures; confidentiality risks; who will access the data and why; cost; others present on the call; no recording unless separately agreed; identity and location check; what to do in an emergency and nearest public facility; copy to the record and to the patient ([Booklet 10 para 5.7](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf); [Techzim summary of MDPCZ policy](https://www.techzim.co.zw/2022/10/what-you-should-know-about-the-recently-published-mdpcz-policy-on-telemedicine/); [Public Health Act ss 34-35](https://bwcimplementation.org/sites/default/files/resource/ZW_Public%20Health%20Act%202018_EN.pdf)). |
| Guardian and proxy consent form | Both | Relationship and legal basis (parent, guardian, court order, written mandate, statutory order of relatives); proof documents; scope of access; adolescent consent for proxies of 12-17 year olds in SA; expiry; re-consent at 18 ([CDPG 2](https://www.potraz.gov.zw/wp-content/uploads/2026/03/Processing-of-Childrens-Personal-Information-Final-002.pdf); [CDPA s 27](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf); [MSAA s 8B](https://www.veritaszim.net/sites/veritas_d/files/Medical%20Services%20Amendment%2C%20Act%20No.%203%20of%202026.pdf)). |
| E-prescription templates | Both | SA: all reg 33 particulars and AES ([reg 33](https://www.acts.co.za/medicines-/n859_33__particulars_which_mus)); ZW: all s 74 particulars, supply limits and markers ([S.I. 150](https://mdpcz.co.zw/external_policies/1682978281Medicines%20and%20Allied%20Substances%20Control%20General%20Regulations%201991SI%20150%20of%201991.pdf)); prescriber's printed name and initials next to the signature (HPCSA rule 15). |
| AI alert disclosure and intended-use statement | Both | What the rules look at, limits and known biases, locked or learning, clinician makes the final decision, how to opt out without losing care, how to challenge ([Booklet 20](https://www.hpcsa-blogs.co.za/wp-content/uploads/2025/11/ETHICAL-GUIDELINES-USE-OF-AI.pdf); [SAHPRA MD08](https://www.sahpra.org.za/wp-content/uploads/2025/09/MD08-20252026_-SAHPRA-Communication-to-Industry-AI-Medical-devices_Acknowledgements.pdf)). |
| PAIA manual | ZA | Contact details of the head and Information Officer; how to request records; categories of records; POPIA section: purposes, data subject categories, recipients, planned transborder flows, security description ([PAIA s 51](https://www.acts.co.za/promotion-of-access-to-/51__manual)). |
| Cookie and local storage notice | Both | What is stored in the browser (session tokens in `sessionStorage`), any captcha or analytics cookies, how to control them. |
| DPO and Information Officer details | Both | Names, email, phone, address; DP2 filing (ZW); eServices registration (SA); deputy arrangements. |
| Record of processing activities | ZW (good practice in SA) | Per module: purpose, legal basis, data categories, data subjects, recipients, processors, transfers, retention, security ([CDPA s 21](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf)). |
| DPIA (also the SA PIIA) | Both | Risks to patients from health data, minors, caregivers, transfers, video, AI alerts; measures; residual risk; DPO sign-off. Submit the transfer DPIA to POTRAZ. |
| Breach response plan | Both | Detection and triage; who decides; POTRAZ Form DP3 within 24 hours ([SI 155 reg 17](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf)); patient notice within 72 hours if high risk; SA eServices report and written notices ([POPIA s 22](https://popia.co.za/section-22-notification-of-security-compromises/)); 21-day report; breach register; vendor notice duties; message templates; evidence preservation and police referral under the Cybercrimes Act. |
| Operator and sub-processor agreements | Both | Instructions only; confidentiality; security measures; immediate breach notice (in hours); sub-processor approval; location of data; return or deletion at end; audit rights; POPIA and CDPA riders; cross-border clauses and data-sharing terms per CDPG 5 para 4. |
| Clinician agreement | Both | Registration and practising certificate warranties per country (MDPCZ, HPCSA) and notice of lapse; practice number; record-keeping duties; telehealth standards (Booklet 10, MDPCZ policy); keep an in-person practice; confidentiality; platform security commitments (doctors carry disciplinary risk); fees billed under the practitioner's practice number with no fee-sharing ([HPCSA rule 7](https://ethiqal.co.za/wp-content/uploads/2019/08/HPCSA-Ethical-and-Professional-Rules-of-the-Health-Profession-Council.pdf)). |
| Records management and retention policy | Both | Retention per category (section 4.4), amendments, secure destruction and register, handling when a doctor leaves, legal holds ([MDPCZ records policy](https://mdpcz.co.zw/policy_guidelines/1681878041Medical%20records%20ammendment.pdf); [Booklet 9](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/11/Booklet-9-Keeping-of-Patient-Records_Review-Draft_-FINAL_Sept-2022.pdf)). |
| Complaints procedure | ZW (good practice SA) | How to complain, acknowledgement, timelines, escalation to the head of the institution, referral to MDPCZ, HPA or HPCSA ([PHA s 41](https://bwcimplementation.org/sites/default/files/resource/ZW_Public%20Health%20Act%202018_EN.pdf)). |
| Information security policy and risk assessment | Both | Access control, MFA, encryption, backups and restore tests, vulnerability management, pen testing, staff training and confidentiality undertakings ([SI 155 reg 16](https://www.veritaszim.net/sites/veritas_d/files/SI%202024-155%20Cyber%20and%20Data%20Protection%20%28Licensing%20of%20Data%20Controllers%20and%20Appointment%20of%20Data%20Protection%20Officers%29%20Regulations,%202024.pdf); [POPIA s 19](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf); [NHA s 17](https://www.acts.co.za/national-health/17__protection_of_health_records)). |
| Data subject request procedure | Both | Intake channels, identity check, deadlines (30 days per MDPCZ), redaction of third-party data, PAIA s 61 health-record consultation, logging. |
| Staff and support confidentiality undertakings | Both | Professional secrecy for anyone with health data access ([CDPA s 12(7)](https://www.veritaszim.net/sites/veritas_d/files/Cyber%20%26%20Data%20Protection%20Act%20Cap1207%20No%205%20of%202021%20gaz%202022-03-11.pdf); [POPIA s 32(2)-(3)](https://www.gov.za/sites/default/files/gcis_document/201409/3706726-11act4of2013popi.pdf); [Booklet 10 para 5.9.1(d)](https://www.hpcsa-blogs.co.za/wp-content/uploads/2022/08/Booklet-10_Telehealth__Dec_2021_.pdf)). |
| Payments and FICA procedure | Both | Settlement model and merchant of record; TPPP or agent status; FX and currency handling; suspicious transaction escalation; refunds ([FICA s 29](https://www.fic.gov.za/wp-content/uploads/2023/10/Financial-Intelligence-Centre-Act-2001-Act-38-of-2001.pdf); [NPS Act s 18](https://www.rbz.co.zw/documents/acts/nationalpaymentsystem_act.pdf)). |

---

## 8. Open questions for lawyers

**Zimbabwe**

1. Is CareNexus a data controller, a processor for doctors and clinics, or a joint controller? Who holds the POTRAZ
   licence, and how are data subjects counted for the tier? Is CareNexus a "health institution" (Public Health Act
   s 38, Medical Services Act s 15, HPA s 99)? Does a purely online platform need HPA registration?
2. With no electronic transactions Act in force, does an electronic click-through or checkbox count as consent "in
   writing" under CDPA ss 11-12, Public Health Act s 39(2)(a) and Medical Services Act s 8G(2)(a)?
3. CDPA s 12(8)-(9): has any authority been designated to issue unique patient identifiers, and how does POTRAZ apply
   this to private platforms?
4. Would POTRAZ treat South Africa, or the chosen hosting country, as adequate? Does CDPG 5 in practice require prior
   authorisation and express consent for every offshore hosting arrangement, given it goes beyond ss 28-29? Has any data
   category been designated under s 28(3)?
5. Can the DPO be outsourced, shared with a South African group company, or based outside Zimbabwe?
6. If the contracting entity is South African, is a designated representative in Zimbabwe required (s 4(3)), and can
   a foreign entity hold a POTRAZ licence (DP1 asks for an incorporation number)?
7. What retention periods does Medical Services Act s 15 impose? Are minimum storage standards under the new
   s 16(2)(e8) coming?
8. Is there a deadline for answering access, correction and objection requests beyond the MDPCZ's 30 days?
9. Do clinician-reviewed AI alerts count as "solely automated" decisions (s 25, reg 10(3))? Would an external AI
   provider need prior authorisation under s 22? Is MCAZ preparing software-as-a-medical-device rules?
10. Can a South Africa-registered doctor consult directly with a patient in Zimbabwe? Does HPA s 129(1)(a) cover a
    remote consult initiated by a Zimbabwe-registered doctor? Ask the MDPCZ registrar (mdpcz@mdpcz.co.zw) for the
    official 2022 telemedicine policy, the international telemedicine policy, and its position on first consultations
    without an in-person visit and on platform operators.
11. Are electronic prescriptions with electronic signatures valid under S.I. 150/1991 s 74(2) ("signed in full")? Will
    pharmacies, the MCAZ and the Pharmacists Council accept them?
12. Confirm that no separate commencement notice was needed for the Medical Services Amendment Act No. 3 of 2026
    (gazetted 7 July 2026, no commencement clause).
13. Are the HPA sections cited current (the consolidated text runs only to Act 28/2004)?
14. Have the US dollar values of the fine levels changed since SI 14A of 2023, and what are levels 5, 6, 10 and 12? Is
    POTRAZ getting administrative fine powers?
15. Consumer Protection Act s 53: can a completed consultation be deducted from a refund when a patient cancels within 7
    days? Does "currency being used by the country" (s 26) require ZiG prices alongside USD?
16. Payments: does a formal appointment as each practice's collection agent bring CareNexus within NPS Act s 18(3)(c)?
    Has the NPS Act been replaced by a payment-services statute? Is RBZ approval needed before launching the payment
    service?
17. Tax and FX: VAT treatment of platform fees versus exempt medical services; the withholding fraction for
    VAT-registered non-resident digital suppliers (3/23 or exempt); whether SI 34 of 2025 validly repealed the SI 81A
    penalties; whether SA receipts of a Zimbabwean entity are declared on Form GSD and subject to the 30% surrender.

**South Africa**

18. Is CareNexus a responsible party or an operator for practices (or both for different data)? Can it rely on
    s 32(1)(a), or does it need s 27(1)(a) consent for all health data? Is it a "health establishment" or "person in
    charge" under NHA ss 13 and 17?
19. Does offshore storage with a cloud host count as a transfer under ss 57(1)(d) and 72? Is EU/GDPR-region hosting
    with a binding DPA "adequate", or should a prior authorisation application be filed anyway?
20. Do the 2026 health information regulations reach CareNexus through insurance claims or employer wellness work?
21. How do POPIA's 18, HPCSA Booklet 9's 12 (own access) and 16 (parent access needs consent), and the Children's Act
    treatment-consent age interact? May an adolescent hide records from a parent or caregiver proxy?
22. Has the HPCSA formally adopted the September 2022 revision of Booklet 9 (records ideally kept indefinitely)?
23. If Zimbabwean staff or systems handle SA users' data, is Zimbabwe's CDPA adequate under s 72(1)(a), or is an
    intra-group agreement needed?
24. Is any health-sector POPIA code of conduct pending (which would change the ss 57-58 position)?
25. E-prescriptions: does HPCSA rule 17(2) (handwritten Schedule 5-8) still apply despite General Regulation 33? Which
    accredited AES products satisfy ECTA s 13(1) for prescribers?
26. Does the rule-based alert, as worded, make CareNexus a SaMD manufacturer needing a s 22C licence? What does Notice
    5855 of 2025 exempt?
27. HPCSA rule 7: is a flat per-consultation platform fee acceptable where a percentage is not? Does any CareNexus
    contracting model with doctors need rule 18 employer approval, and by when?
28. Payments: register as a TPPP with the SARB, or have practices as merchants of record? How will the Authorisation
    Framework change this, and what is CareNexus's FICA status as a collecting intermediary?
29. VAT: are pass-through consultation fees and the platform fee "electronic services" for a foreign supplier, and
    which entity should register?
30. Would any subscription or bundled plan be "the business of a medical scheme" after *Epione*, and could a s 8(h)
    exemption be obtained?

Settled in this revision (no longer open): the 2025 POPIA regulation amendments and the 1 April 2025 eServices date are
confirmed by the Regulator; the PAIA small-body exemption lapsed on 31 December 2021 (multiple secondary sources); a
Zimbabwe-registered doctor needs HPCSA registration to consult a patient in South Africa (Booklet 10 para 5.1(c)).
