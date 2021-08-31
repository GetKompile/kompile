import numpy as np
import timeit
import torch
from torchvision import models, transforms
import torch.nn as nn
import torch.nn.functional as F
from torch.autograd import Variable
# Specify a path

modelPath = "agerace_v2.pt"
device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
human_readable_labels = {
    'ageRange' : ['12-17', '18-23', '24-30', '31-40', '41-50', '51-60', '61+'],
    "gender": ["Female", "Male"],
    "race": ["Chinese", "Indian", "Malay", "Others"]
}
imsize = 224


class Resnet50(nn.Module):
    def __init__(self, ageRange_Nodes=7, gender_Nodes=2, race_Nodes=4):
        super().__init__()

        self.ageRange_Nodes = ageRange_Nodes
        self.gender_Nodes = gender_Nodes
        self.race_Nodes = race_Nodes

        # using resnet50 as the backbone model *here
        resnet50 = models.resnet50(pretrained=True)
        num_features = resnet50.fc.in_features
        resnet50.fc = nn.Linear(num_features, 512)

        self.backbone_model = resnet50
        self.x1 = nn.Linear(512, 256) # number of output nodes from backbone model (input nodes of fc layer in the ori model)
        nn.init.xavier_normal_(self.x1.weight)

        self.x2 = nn.Linear(512, 256) # number of output nodes from backbone model (input nodes of fc layer in the ori model)
        nn.init.xavier_normal_(self.x2.weight)

        self.x3 = nn.Linear(512, 256) # number of output nodes from backbone model (input nodes of fc layer in the ori model)
        nn.init.xavier_normal_(self.x3.weight)

        self.bn = nn.BatchNorm1d(512) # dense layer batchnorm, just now is 2d for conv one

        # heads
        self.y_ageRange = nn.Linear(256, self.ageRange_Nodes)
        nn.init.xavier_normal_(self.y_ageRange.weight)
        self.y_gender = nn.Linear(256, self.gender_Nodes)
        nn.init.xavier_normal_(self.y_gender.weight)
        self.y_race = nn.Linear(256, self.race_Nodes)
        nn.init.xavier_normal_(self.y_race.weight)

    def forward(self, x):
        x = self.backbone_model(x)
        x = self.bn(x)
        x = F.relu(x)

        x1_output = self.x1(x)
        x2_output = self.x2(x)
        x3_output = self.x3(x)

        y_ageRange = self.y_ageRange(x1_output)
        y_gender = self.y_gender(x2_output)
        y_race = self.y_race(x3_output)

        return y_ageRange, y_gender, y_race


 # Initialize model
model = Resnet50(7, 2, 4)

# Load weights with model
if(torch.cuda.device_count() < 1):
    model.load_state_dict(torch.load(modelPath, map_location='cpu'))
else:
    model.load_state_dict(torch.load(modelPath, map_location='cuda:0'))
model.eval()
torch.no_grad()
input_arr_dict = torch.from_numpy(np.ones((1, 3,224,224))).to(device)
num_runs = 1
num_repetition = 1

def run():
   output = model(input_arr_dict)
duration = timeit.Timer(run).timeit(number=num_runs)
avg_time = duration
print('Average execution time was ' + str(avg_time))

