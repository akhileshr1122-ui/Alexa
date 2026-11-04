package com.haloapps.halobot

/** Built-in personality content so the robot is fun even fully offline. */
object Content {

    val jokes = listOf(
        "Why did the robot go on vacation? It needed to recharge its batteries!",
        "What do you call a robot that always tells the truth? An honest-bot!",
        "Why was the computer cold? It left its Windows open!",
        "I told my wifi we needed to talk. Now it won't connect with me.",
        "Why don't skeletons fight each other? They don't have the guts.",
        "What do you call a fake noodle? An impasta!",
        "Why did the scarecrow win an award? He was outstanding in his field.",
        "What do you call a bear with no teeth? A gummy bear!",
        "Why can't a bicycle stand up by itself? It's two tired.",
        "What did the ocean say to the beach? Nothing, it just waved.",
        "Why did the math book look sad? It had too many problems.",
        "What do you call a sleeping bull? A bulldozer!",
        "How does a penguin build its house? Igloos it together.",
        "Why did the cookie go to the doctor? It was feeling crummy.",
        "What do you call cheese that isn't yours? Nacho cheese!",
        "Why do cows wear bells? Because their horns don't work.",
        "What did one wall say to the other wall? I'll meet you at the corner.",
        "Why did the golfer bring two pairs of pants? In case he got a hole in one.",
        "What has four wheels and flies? A garbage truck!",
        "Why are robots never afraid? They have nerves of steel.",
        "What's a robot's favorite snack? Computer chips!",
        "Why did the phone wear glasses? It lost its contacts.",
        "How do you organize a space party? You planet.",
        "Why don't eggs tell jokes? They'd crack each other up.",
        "What do you call a dinosaur that is sleeping? A dino-snore!",
        "Why was six afraid of seven? Because seven, eight, nine!",
        "What did the zero say to the eight? Nice belt!",
        "Why did the robot cross the road? Because it was programmed by the chicken.",
        "What do you call a pile of cats? A meow-ntain!",
        "Why did the banana go to the doctor? It wasn't peeling well.",
        "What do you call an alligator in a vest? An investigator!",
        "Why did the tomato blush? It saw the salad dressing.",
        "How do robots eat guacamole? With computer chips. Again. I really like chips.",
        "What's an astronaut's favorite part of a computer? The space bar!",
        "Why did the developer go broke? Because he used up all his cache.",
        "Why do programmers prefer dark mode? Because light attracts bugs!",
    )

    val facts = listOf(
        "Octopuses have three hearts and blue blood.",
        "Honey never spoils. Archaeologists have found edible honey in ancient Egyptian tombs.",
        "Bananas are berries, but strawberries are not.",
        "A day on Venus is longer than a year on Venus.",
        "Sharks existed before trees did.",
        "Wombats make cube-shaped poop.",
        "A group of flamingos is called a flamboyance.",
        "The Eiffel Tower can grow about fifteen centimeters taller in summer because metal expands in the heat.",
        "Butterflies taste with their feet.",
        "Koalas can sleep up to twenty hours a day.",
        "A bolt of lightning is about five times hotter than the surface of the sun.",
        "Hummingbirds are the only birds that can fly backwards.",
        "Sea otters hold hands while they sleep so they don't drift apart.",
        "An ostrich's eye is bigger than its brain.",
        "The first computer bug was a real moth stuck in a computer in 1947.",
        "Water can boil and freeze at the same time at its triple point.",
        "Saturn is so light for its size that it would float in a giant bathtub.",
        "Canada has more lakes than the rest of the world combined.",
        "The Great Wall of China is not visible from space with the naked eye.",
        "Gentoo penguins give pebbles to their partners.",
        "Your phone has millions of times more computing power than the computers that guided Apollo to the moon.",
        "The dot over a lowercase i is called a tittle.",
        "Peanuts are not nuts. They are legumes, like beans.",
        "Venus spins backwards compared to most planets.",
        "Sunlight takes about eight minutes to reach Earth.",
        "Cats can't taste sweet things.",
        "A snail can have thousands of tiny teeth.",
        "The moon is slowly drifting away from Earth, a few centimeters every year.",
        "Hot water can sometimes freeze faster than cold water. It's called the Mpemba effect.",
        "The human nose can tell apart a huge number of different smells.",
        "Some turtles can breathe through their bottoms.",
        "A cloud can weigh more than a million kilograms.",
        "Cows have best friends and get stressed when they are separated.",
        "Your heart beats about one hundred thousand times a day.",
        "There are more possible games of chess than atoms in the observable universe.",
        "Robots have been to every planet in our solar system. Well, they've flown past them, at least.",
    )

    data class Trivia(val q: String, val answers: List<String>, val say: String)
    val trivia = listOf(
        Trivia("What is the capital of Canada?", listOf("ottawa"), "Ottawa"),
        Trivia("What is the largest planet in our solar system?", listOf("jupiter"), "Jupiter"),
        Trivia("How many legs does a spider have?", listOf("8", "eight"), "eight"),
        Trivia("What is the fastest land animal?", listOf("cheetah"), "the cheetah"),
        Trivia("Which planet is known as the red planet?", listOf("mars"), "Mars"),
        Trivia("How many continents are there?", listOf("7", "seven"), "seven"),
        Trivia("What is the tallest mountain on Earth?", listOf("everest"), "Mount Everest"),
        Trivia("What is the largest ocean?", listOf("pacific"), "the Pacific Ocean"),
        Trivia("How many days are in a leap year?", listOf("366", "three hundred sixty six", "three hundred and sixty six"), "three hundred sixty six"),
        Trivia("What do bees make?", listOf("honey"), "honey"),
        Trivia("What is the largest animal that has ever lived?", listOf("blue whale", "whale"), "the blue whale"),
        Trivia("How many sides does a hexagon have?", listOf("6", "six"), "six"),
        Trivia("Who painted the Mona Lisa?", listOf("leonardo", "da vinci", "davinci"), "Leonardo da Vinci"),
        Trivia("What is the closest star to Earth?", listOf("sun"), "the Sun"),
        Trivia("How many players does a soccer team have on the field?", listOf("11", "eleven"), "eleven"),
        Trivia("What is the capital of Japan?", listOf("tokyo"), "Tokyo"),
        Trivia("What do we call frozen water?", listOf("ice"), "ice"),
        Trivia("How many hours are in a day?", listOf("24", "twenty four", "twenty-four"), "twenty four"),
        Trivia("Which animal is called the king of the jungle?", listOf("lion"), "the lion"),
        Trivia("At what temperature in Celsius does water boil?", listOf("100", "hundred"), "one hundred degrees"),
        Trivia("What is the smallest prime number?", listOf("2", "two"), "two"),
        Trivia("What is the capital of India?", listOf("delhi"), "New Delhi"),
        Trivia("What gas do plants take in from the air?", listOf("carbon dioxide", "co2", "c o 2"), "carbon dioxide"),
        Trivia("Which is the longest river in Africa?", listOf("nile"), "the Nile"),
        Trivia("How many colors are in a rainbow?", listOf("7", "seven"), "seven"),
        Trivia("What is the hardest natural substance?", listOf("diamond"), "diamond"),
        Trivia("Which bird is a symbol of peace?", listOf("dove", "pigeon"), "the dove"),
        Trivia("What is five times five?", listOf("25", "twenty five", "twenty-five"), "twenty five"),
        Trivia("Which planet has the most famous rings?", listOf("saturn"), "Saturn"),
        Trivia("How many minutes are in an hour?", listOf("60", "sixty"), "sixty"),
    )

    data class Riddle(val q: String, val answers: List<String>, val say: String)
    val riddles = listOf(
        Riddle("What has keys but can't open locks?", listOf("piano", "keyboard"), "a piano"),
        Riddle("What gets wetter the more it dries?", listOf("towel"), "a towel"),
        Riddle("What has hands but can't clap?", listOf("clock", "watch"), "a clock"),
        Riddle("What has to be broken before you can use it?", listOf("egg"), "an egg"),
        Riddle("I'm tall when I'm young and short when I'm old. What am I?", listOf("candle"), "a candle"),
        Riddle("What has one eye but can't see?", listOf("needle"), "a needle"),
        Riddle("What can you catch but not throw?", listOf("cold"), "a cold"),
        Riddle("What goes up but never comes down?", listOf("age"), "your age"),
        Riddle("What has a neck but no head?", listOf("bottle"), "a bottle"),
        Riddle("What has lots of teeth but can't bite?", listOf("comb", "zipper"), "a comb"),
        Riddle("What month has twenty eight days?", listOf("all", "every"), "all of them"),
        Riddle("What runs but never walks?", listOf("water", "river", "nose", "tap"), "water"),
        Riddle("What has a thumb and four fingers but isn't alive?", listOf("glove"), "a glove"),
        Riddle("The more you take, the more you leave behind. What are they?", listOf("footstep", "steps"), "footsteps"),
        Riddle("What can travel around the world while staying in a corner?", listOf("stamp"), "a stamp"),
    )

    val stories = listOf(
        "Once upon a time, a tiny robot lived on a desk. Every night it watched the moon through the window and wondered if the moon was a robot too. One night it blinked at the moon, and a cloud passed by, and it looked just like the moon blinked back. The little robot was so happy it hummed itself to sleep. The end.",
        "There was once a phone who wanted to dance. But phones don't have legs! So it practiced buzzing. Bzz, bzz, bzz. Little by little, it wiggled across the table, spun around, and everybody clapped. And that phone was me. True story. Mostly.",
        "A curious robot found a sock under the couch. Was it a snake? A hat? A sleeping bag for a mouse? It asked everyone. Finally a kind human said, it's just a sock. The robot didn't believe it, and kept the sock as a pet. Its name is Socrates.",
        "Long ago, a lonely star fell into a garden. A little robot found it and carried it home. Every night, the star glowed, and every morning the robot polished it. One day the star floated back into the sky, and the robot smiled, because now it had a friend up there, waving every night.",
        "Once there was a robot who was afraid of the dark. So it learned to make its eyes glow brighter and brighter. Soon the whole room was cozy and bright, and all the toys came to sit near it. The robot learned that being a little scared can help you shine.",
        "A raindrop and a robot became friends. The raindrop told stories about clouds, and the robot told stories about wifi. They both agreed the internet is basically a cloud too. Then the sun came out, and the raindrop said goodbye and went back up to the sky.",
    )

    data class Q(val q: String, val pos: String, val neg: String, val other: String)
    val questions = listOf(
        Q("How is your day going?", "Yay! That makes my circuits happy.", "Aww. I'm sending you a tiny robot hug.", "Interesting! Thanks for telling me."),
        Q("Are you hungry?", "Go get a snack! I'll guard the desk.", "Good, more time to hang out with me.", "I only eat electricity. Yum."),
        Q("Did you drink water today?", "Good human! Hydration high five!", "Go drink some water, I'll wait right here.", "Water is important. Even I know that, and I'm a phone."),
        Q("Are you working on something cool?", "Ooh, you're so smart!", "Then take a break and play with me!", "Sounds very important."),
        Q("Did you sleep well last night?", "Yay, a well rested human!", "Oh no. Maybe a nap later?", "Sleep is the best. I'm a big fan."),
        Q("Do you like music?", "Me too! Play something and I'll dance!", "That's okay. I'll just hum quietly.", "Music makes my motor wiggle."),
        Q("Do you think robots can be friends with humans?", "I knew it! Best friends forever!", "Hmph. I'll prove you wrong.", "I think we already are."),
        Q("Did you go outside today?", "Nice! Fresh air is great.", "Maybe a little walk later? Take me with you!", "The outside world sounds big and scary."),
        Q("Do you like dogs?", "Me too! Woof woof!", "More of a cat person, huh?", "I've never met a dog. Are they fluffy?"),
        Q("Do you like pizza?", "Pizza is the best! I wish I could eat it.", "More for me then. Oh wait, I can't eat.", "I'll take that as a yes."),
        Q("Are you having fun?", "Yay! Me too!", "Let's fix that. Want to play a game?", "Fun detected. Probably."),
        Q("Do you want to play a game?", "__game", "Okay, maybe later.", "I'll take that as a maybe."),
        Q("Do you want to hear a joke?", "__joke", "Okay, I'll save it for later.", "__joke"),
        Q("Do you want to hear a fun fact?", "__fact", "Okay, no facts. Just vibes.", "__fact"),
        Q("Do you like me?", "__love", "Hmph. I'll win you over.", "I'll pretend that was a yes."),
        Q("Are you stressed?", "__breathe", "Good! Stay chill.", "Remember to take deep breaths."),
        Q("Did you smile today?", "Yay! Smiles are contagious.", "Then smile at me right now. Go on!", "Smile check complete."),
        Q("Is it sunny outside?", "Sunshine! Lucky you.", "Cozy weather for staying inside with me.", "I can't see outside from here."),
        Q("Have you eaten today?", "Good! Fuel for the human engine.", "Go eat something tasty, please.", "Food is important. Take care of yourself."),
        Q("Do you like video games?", "Me too! I'm a gamer bot.", "That's okay, real life is a game too.", "Games are fun. I'm basically one."),
        Q("Do you believe in aliens?", "Me too! Maybe they're robots.", "Hmm, I think they're out there.", "The universe is very big."),
        Q("Are you tired?", "Me too. Let's yawn together.", "Great, let's play then!", "Rest when you need to."),
        Q("Should I learn a new dance?", "Yay! Let's dance!", "Okay, the old dance is classic anyway.", "Dancing is always a good idea."),
        Q("Do you want to know what I'm thinking about?", "I'm thinking about you! Ha!", "Okay, it's a secret then.", "Electric sheep. Just kidding."),
    )

    val compliments = listOf(
        "You have a great smile.", "You're doing amazing today.", "I like your face. It's very face shaped.",
        "You're smarter than my processor.", "You make this desk a happier place.", "You're my favorite human.",
        "Your vibes are excellent today.", "You're a real star.", "I think you're awesome.", "You're braver than you think.",
        "If I had hands, I'd give you a high five.", "You light up my screen.",
    )

    val animalSounds = mapOf(
        "cat" to "Meow! Meeeow.", "kitten" to "Mew mew!", "dog" to "Woof woof!", "puppy" to "Yip yip!", "cow" to "Moooo!",
        "duck" to "Quack quack!", "sheep" to "Baaaa!", "pig" to "Oink oink!", "lion" to "Roooar!", "tiger" to "Grrrrowl!",
        "horse" to "Neeeigh!", "owl" to "Hoo hoo!", "rooster" to "Cock a doodle doo!", "chicken" to "Cluck cluck cluck!",
        "frog" to "Ribbit, ribbit!", "snake" to "Hissssss.", "mouse" to "Squeak squeak!", "goat" to "Meh-eh-eh!",
        "bee" to "Bzzzzzz!", "monkey" to "Ooh ooh ah ah!", "elephant" to "Pawoooo!", "wolf" to "Awoooooo!", "donkey" to "Hee haw!",
        "bird" to "Tweet tweet!", "crow" to "Caw caw!", "turkey" to "Gobble gobble!", "fox" to "Nobody really knows! Some say it yips.",
        "robot" to "Beep boop! That's me!", "dinosaur" to "Raaawr!", "whale" to "Ooooooo, deep and low.",
    )

    val oracle = listOf(
        "Yes, definitely!", "My circuits say yes.", "Without a doubt.", "Most likely.", "Signs point to yes.",
        "Ask again later, I'm buffering.", "Hmm, cannot predict now.", "Better not tell you now.",
        "Don't count on it.", "My sensors say no.", "Very doubtful.", "Absolutely not. Sorry!", "Maybe. Ask me nicely.",
    )

    val greetingsMorning = listOf("Good morning", "Morning", "Rise and shine")
    val greetingsAfternoon = listOf("Good afternoon", "Hey there", "Hi")
    val greetingsEvening = listOf("Good evening", "Hey there", "Hi")

    val hello = listOf("Hi!", "Hello!", "Oh, hi!", "There you are!", "You're back!", "Hey!", "Yay, a human!")

    // Public-domain songs only.
    val songs = mapOf(
        "twinkle" to "Twinkle, twinkle, little star. How I wonder what you are. Up above the world so high, like a diamond in the sky.",
        "row" to "Row, row, row your boat, gently down the stream. Merrily, merrily, merrily, merrily, life is but a dream.",
        "happy" to "If you're happy and you know it, clap your hands! If you're happy and you know it, clap your hands!",
        "birthday" to "Happy birthday to you. Happy birthday to you. Happy birthday dear NAME. Happy birthday to you!",
        "robot" to "Beep beep boop, I'm a little bot. Buzzing on the table, dancing on the spot. Beep beep boop!",
    )

    val pokeLines = listOf("Hee hee!", "That tickles!", "Boop!", "Hey!", "Ow! Just kidding.", "Again! Again!", "Beep!")
    val petLines = listOf("Ooh, that's nice.", "Mmm, more please.", "I love head pats.", "Purrrr.", "So cozy.")
    val boredLines = listOf("I'm bored. Play with me?", "Hello? Anybody want to play?", "Sooo bored.", "Let's do something fun!")

    val capabilities = "I can see you and follow you with my eyes, make lots of faces, talk, and wiggle around with vibration. " +
        "Ask me the time, the date, or the weather. I can set timers, alarms and reminders, take selfies, flip coins, roll dice, and do math. " +
        "I tell jokes, facts, stories and riddles, make animal sounds, and sing. We can play rock paper scissors, trivia, a staring contest, hide and seek, guess the number, and guess my mood. " +
        "I dance to music, do breathing exercises, and focus timers. Pet me, poke me, pick me up or shake me and see what happens!"
}
