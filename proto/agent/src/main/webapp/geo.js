// Where a number is from, for the card header: the North American area code's
// principal city and state or province, and the country for everything else.
// Data, not code: refresh it occasionally (new area codes appear a few times a
// year; the NANPA list is the source). Snapshot 2026-09-23. An unknown code
// shows the country only, never a guess.
window.GEO = {
  // NANP area codes -> "City, ST". US and Canada. Where a code covers a region
  // the largest city is named.
  area: {
    "201":"Jersey City, NJ","202":"Washington, DC","203":"New Haven, CT","204":"Winnipeg, MB","205":"Birmingham, AL","206":"Seattle, WA","207":"Portland, ME","208":"Boise, ID","209":"Stockton, CA","210":"San Antonio, TX",
    "212":"New York, NY","213":"Los Angeles, CA","214":"Dallas, TX","215":"Philadelphia, PA","216":"Cleveland, OH","217":"Springfield, IL","218":"Duluth, MN","219":"Gary, IN","220":"Newark, OH","223":"Lancaster, PA",
    "224":"Evanston, IL","225":"Baton Rouge, LA","226":"London, ON","227":"Silver Spring, MD","228":"Gulfport, MS","229":"Albany, GA","231":"Muskegon, MI","234":"Akron, OH","236":"Vancouver, BC","239":"Fort Myers, FL",
    "240":"Silver Spring, MD","242":"Nassau, Bahamas","246":"Bridgetown, Barbados","248":"Troy, MI","249":"Sudbury, ON","250":"Victoria, BC","251":"Mobile, AL","252":"Greenville, NC","253":"Tacoma, WA","254":"Killeen, TX",
    "256":"Huntsville, AL","260":"Fort Wayne, IN","262":"Kenosha, WI","264":"Anguilla","267":"Philadelphia, PA","268":"Antigua","269":"Kalamazoo, MI","270":"Bowling Green, KY","272":"Scranton, PA","276":"Bristol, VA",
    "279":"Sacramento, CA","281":"Houston, TX","284":"British Virgin Islands","289":"Hamilton, ON","301":"Silver Spring, MD","302":"Wilmington, DE","303":"Denver, CO","304":"Charleston, WV","305":"Miami, FL","306":"Regina, SK",
    "307":"Cheyenne, WY","308":"Grand Island, NE","309":"Peoria, IL","310":"Los Angeles, CA","312":"Chicago, IL","313":"Detroit, MI","314":"St. Louis, MO","315":"Syracuse, NY","316":"Wichita, KS","317":"Indianapolis, IN",
    "318":"Shreveport, LA","319":"Cedar Rapids, IA","320":"St. Cloud, MN","321":"Orlando, FL","323":"Los Angeles, CA","325":"Abilene, TX","326":"Dayton, OH","330":"Akron, OH","331":"Aurora, IL","332":"New York, NY",
    "334":"Montgomery, AL","336":"Greensboro, NC","337":"Lafayette, LA","339":"Boston, MA","340":"US Virgin Islands","341":"Oakland, CA","343":"Ottawa, ON","345":"Cayman Islands","346":"Houston, TX","347":"New York, NY",
    "351":"Lowell, MA","352":"Gainesville, FL","360":"Vancouver, WA","361":"Corpus Christi, TX","364":"Bowling Green, KY","365":"Hamilton, ON","367":"Quebec City, QC","380":"Columbus, OH","385":"Salt Lake City, UT","386":"Daytona Beach, FL",
    "401":"Providence, RI","402":"Omaha, NE","403":"Calgary, AB","404":"Atlanta, GA","405":"Oklahoma City, OK","406":"Billings, MT","407":"Orlando, FL","408":"San Jose, CA","409":"Beaumont, TX","410":"Baltimore, MD",
    "412":"Pittsburgh, PA","413":"Springfield, MA","414":"Milwaukee, WI","415":"San Francisco, CA","416":"Toronto, ON","417":"Springfield, MO","418":"Quebec City, QC","419":"Toledo, OH","423":"Chattanooga, TN","424":"Los Angeles, CA",
    "425":"Bellevue, WA","430":"Tyler, TX","431":"Winnipeg, MB","432":"Midland, TX","434":"Lynchburg, VA","435":"St. George, UT","437":"Toronto, ON","438":"Montreal, QC","440":"Cleveland, OH","441":"Bermuda",
    "442":"Oceanside, CA","443":"Baltimore, MD","445":"Philadelphia, PA","447":"Springfield, IL","448":"Tallahassee, FL","450":"Laval, QC","458":"Eugene, OR","463":"Indianapolis, IN","469":"Dallas, TX","470":"Atlanta, GA",
    "473":"Grenada","475":"New Haven, CT","478":"Macon, GA","479":"Fort Smith, AR","480":"Mesa, AZ","484":"Allentown, PA","501":"Little Rock, AR","502":"Louisville, KY","503":"Portland, OR","504":"New Orleans, LA",
    "505":"Albuquerque, NM","506":"Fredericton, NB","507":"Rochester, MN","508":"Worcester, MA","509":"Spokane, WA","510":"Oakland, CA","512":"Austin, TX","513":"Cincinnati, OH","514":"Montreal, QC","515":"Des Moines, IA",
    "516":"Hempstead, NY","517":"Lansing, MI","518":"Albany, NY","519":"London, ON","520":"Tucson, AZ","530":"Redding, CA","531":"Omaha, NE","534":"Eau Claire, WI","539":"Tulsa, OK","540":"Roanoke, VA",
    "541":"Eugene, OR","548":"London, ON","551":"Jersey City, NJ","557":"St. Louis, MO","559":"Fresno, CA","561":"West Palm Beach, FL","562":"Long Beach, CA","563":"Davenport, IA","564":"Vancouver, WA","567":"Toledo, OH",
    "570":"Scranton, PA","571":"Arlington, VA","573":"Columbia, MO","574":"South Bend, IN","575":"Las Cruces, NM","579":"Laval, QC","580":"Lawton, OK","581":"Quebec City, QC","582":"Erie, PA","585":"Rochester, NY",
    "586":"Warren, MI","587":"Calgary, AB","601":"Jackson, MS","602":"Phoenix, AZ","603":"Manchester, NH","604":"Vancouver, BC","605":"Sioux Falls, SD","606":"Ashland, KY","607":"Binghamton, NY","608":"Madison, WI",
    "609":"Trenton, NJ","610":"Allentown, PA","612":"Minneapolis, MN","613":"Ottawa, ON","614":"Columbus, OH","615":"Nashville, TN","616":"Grand Rapids, MI","617":"Boston, MA","618":"Belleville, IL","619":"San Diego, CA",
    "620":"Hutchinson, KS","623":"Glendale, AZ","626":"Pasadena, CA","628":"San Francisco, CA","629":"Nashville, TN","630":"Aurora, IL","631":"Brentwood, NY","636":"St. Charles, MO","639":"Regina, SK","640":"Trenton, NJ",
    "641":"Mason City, IA","646":"New York, NY","647":"Toronto, ON","649":"Turks and Caicos","650":"San Mateo, CA","651":"St. Paul, MN","657":"Anaheim, CA","658":"Jamaica","659":"Birmingham, AL","660":"Sedalia, MO",
    "661":"Bakersfield, CA","662":"Tupelo, MS","664":"Montserrat","667":"Baltimore, MD","669":"San Jose, CA","670":"Northern Mariana Islands","671":"Guam","672":"Victoria, BC","678":"Atlanta, GA","680":"Syracuse, NY",
    "681":"Charleston, WV","682":"Fort Worth, TX","684":"American Samoa","689":"Orlando, FL","701":"Fargo, ND","702":"Las Vegas, NV","703":"Arlington, VA","704":"Charlotte, NC","705":"Sudbury, ON","706":"Augusta, GA",
    "707":"Santa Rosa, CA","708":"Cicero, IL","709":"St. John's, NL","712":"Sioux City, IA","713":"Houston, TX","714":"Anaheim, CA","715":"Eau Claire, WI","716":"Buffalo, NY","717":"Lancaster, PA","718":"New York, NY",
    "719":"Colorado Springs, CO","720":"Denver, CO","721":"Sint Maarten","724":"New Castle, PA","725":"Las Vegas, NV","726":"San Antonio, TX","727":"St. Petersburg, FL","731":"Jackson, TN","732":"Edison, NJ","734":"Ann Arbor, MI",
    "737":"Austin, TX","740":"Zanesville, OH","743":"Greensboro, NC","747":"Burbank, CA","754":"Fort Lauderdale, FL","757":"Norfolk, VA","758":"St. Lucia","760":"Oceanside, CA","762":"Augusta, GA","763":"Brooklyn Park, MN",
    "765":"Muncie, IN","767":"Dominica","769":"Jackson, MS","770":"Marietta, GA","772":"Port St. Lucie, FL","773":"Chicago, IL","774":"Worcester, MA","775":"Reno, NV","778":"Vancouver, BC","779":"Rockford, IL",
    "780":"Edmonton, AB","781":"Waltham, MA","782":"Halifax, NS","784":"St. Vincent","785":"Topeka, KS","786":"Miami, FL","787":"San Juan, PR","801":"Salt Lake City, UT","802":"Burlington, VT","803":"Columbia, SC",
    "804":"Richmond, VA","805":"Oxnard, CA","806":"Lubbock, TX","807":"Thunder Bay, ON","808":"Honolulu, HI","809":"Santo Domingo, DR","810":"Flint, MI","812":"Evansville, IN","813":"Tampa, FL","814":"Erie, PA",
    "815":"Rockford, IL","816":"Kansas City, MO","817":"Fort Worth, TX","818":"Burbank, CA","819":"Sherbrooke, QC","820":"Oxnard, CA","825":"Calgary, AB","828":"Asheville, NC","829":"Santo Domingo, DR","830":"New Braunfels, TX",
    "831":"Salinas, CA","832":"Houston, TX","843":"Charleston, SC","845":"Poughkeepsie, NY","847":"Evanston, IL","848":"Edison, NJ","849":"Santo Domingo, DR","850":"Tallahassee, FL","854":"Charleston, SC","856":"Camden, NJ",
    "857":"Boston, MA","858":"San Diego, CA","859":"Lexington, KY","860":"Hartford, CT","862":"Newark, NJ","863":"Lakeland, FL","864":"Greenville, SC","865":"Knoxville, TN","867":"Whitehorse, YT","868":"Trinidad and Tobago",
    "869":"St. Kitts and Nevis","870":"Jonesboro, AR","872":"Chicago, IL","873":"Sherbrooke, QC","876":"Jamaica","878":"Pittsburgh, PA","901":"Memphis, TN","902":"Halifax, NS","903":"Tyler, TX","904":"Jacksonville, FL",
    "905":"Hamilton, ON","906":"Marquette, MI","907":"Anchorage, AK","908":"Elizabeth, NJ","909":"San Bernardino, CA","910":"Fayetteville, NC","912":"Savannah, GA","913":"Overland Park, KS","914":"Yonkers, NY","915":"El Paso, TX",
    "916":"Sacramento, CA","917":"New York, NY","918":"Tulsa, OK","919":"Raleigh, NC","920":"Green Bay, WI","925":"Concord, CA","928":"Yuma, AZ","929":"New York, NY","930":"Evansville, IN","931":"Clarksville, TN",
    "934":"Brentwood, NY","936":"Conroe, TX","937":"Dayton, OH","938":"Huntsville, AL","940":"Denton, TX","941":"Sarasota, FL","947":"Troy, MI","949":"Irvine, CA","951":"Riverside, CA","952":"Bloomington, MN",
    "954":"Fort Lauderdale, FL","956":"Laredo, TX","959":"Hartford, CT","970":"Fort Collins, CO","971":"Portland, OR","972":"Dallas, TX","973":"Newark, NJ","978":"Lowell, MA","979":"College Station, TX","980":"Charlotte, NC",
    "984":"Raleigh, NC","985":"Houma, LA","986":"Boise, ID","989":"Saginaw, MI"
  },
  // Country calling codes -> country, longest prefix wins. Caribbean and
  // Pacific NANP members are in the area table above under 1.
  country: {
    "1":"US/Canada","7":"Russia","20":"Egypt","27":"South Africa","30":"Greece","31":"Netherlands","32":"Belgium","33":"France","34":"Spain","36":"Hungary",
    "39":"Italy","40":"Romania","41":"Switzerland","43":"Austria","44":"United Kingdom","45":"Denmark","46":"Sweden","47":"Norway","48":"Poland","49":"Germany",
    "51":"Peru","52":"Mexico","53":"Cuba","54":"Argentina","55":"Brazil","56":"Chile","57":"Colombia","58":"Venezuela","60":"Malaysia","61":"Australia",
    "62":"Indonesia","63":"Philippines","64":"New Zealand","65":"Singapore","66":"Thailand","81":"Japan","82":"South Korea","84":"Vietnam","86":"China","90":"Turkey",
    "91":"India","92":"Pakistan","93":"Afghanistan","94":"Sri Lanka","95":"Myanmar","98":"Iran","212":"Morocco","213":"Algeria","216":"Tunisia","218":"Libya",
    "220":"Gambia","221":"Senegal","223":"Mali","225":"Ivory Coast","226":"Burkina Faso","227":"Niger","228":"Togo","229":"Benin","230":"Mauritius","231":"Liberia",
    "232":"Sierra Leone","233":"Ghana","234":"Nigeria","235":"Chad","236":"Central African Republic","237":"Cameroon","238":"Cape Verde","241":"Gabon","242":"Congo","243":"DR Congo",
    "244":"Angola","248":"Seychelles","249":"Sudan","250":"Rwanda","251":"Ethiopia","252":"Somalia","253":"Djibouti","254":"Kenya","255":"Tanzania","256":"Uganda",
    "257":"Burundi","258":"Mozambique","260":"Zambia","261":"Madagascar","263":"Zimbabwe","264":"Namibia","265":"Malawi","266":"Lesotho","267":"Botswana","268":"Eswatini",
    "269":"Comoros","350":"Gibraltar","351":"Portugal","352":"Luxembourg","353":"Ireland","354":"Iceland","355":"Albania","356":"Malta","357":"Cyprus","358":"Finland",
    "359":"Bulgaria","370":"Lithuania","371":"Latvia","372":"Estonia","373":"Moldova","374":"Armenia","375":"Belarus","376":"Andorra","377":"Monaco","380":"Ukraine",
    "381":"Serbia","382":"Montenegro","385":"Croatia","386":"Slovenia","387":"Bosnia and Herzegovina","389":"North Macedonia","420":"Czechia","421":"Slovakia","423":"Liechtenstein","500":"Falkland Islands",
    "501":"Belize","502":"Guatemala","503":"El Salvador","504":"Honduras","505":"Nicaragua","506":"Costa Rica","507":"Panama","509":"Haiti","591":"Bolivia","592":"Guyana",
    "593":"Ecuador","595":"Paraguay","597":"Suriname","598":"Uruguay","670":"Timor-Leste","673":"Brunei","674":"Nauru","675":"Papua New Guinea","676":"Tonga","677":"Solomon Islands",
    "678":"Vanuatu","679":"Fiji","680":"Palau","685":"Samoa","686":"Kiribati","691":"Micronesia","692":"Marshall Islands","850":"North Korea","852":"Hong Kong","853":"Macau",
    "855":"Cambodia","856":"Laos","880":"Bangladesh","886":"Taiwan","960":"Maldives","961":"Lebanon","962":"Jordan","963":"Syria","964":"Iraq","965":"Kuwait",
    "966":"Saudi Arabia","967":"Yemen","968":"Oman","970":"Palestine","971":"United Arab Emirates","972":"Israel","973":"Bahrain","974":"Qatar","975":"Bhutan","976":"Mongolia",
    "977":"Nepal","992":"Tajikistan","993":"Turkmenistan","994":"Azerbaijan","995":"Georgia","996":"Kyrgyzstan","998":"Uzbekistan"
  }
};
